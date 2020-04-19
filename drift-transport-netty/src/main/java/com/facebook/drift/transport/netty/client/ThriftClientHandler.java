/*
 * Copyright (C) 2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.drift.transport.netty.client;

import com.facebook.airlift.log.Logger;
import com.facebook.drift.TApplicationException;
import com.facebook.drift.TException;
import com.facebook.drift.codec.ThriftCodec;
import com.facebook.drift.codec.internal.ProtocolReader;
import com.facebook.drift.codec.internal.ProtocolWriter;
import com.facebook.drift.codec.metadata.ThriftType;
import com.facebook.drift.protocol.TMessage;
import com.facebook.drift.protocol.TProtocolReader;
import com.facebook.drift.protocol.TProtocolWriter;
import com.facebook.drift.protocol.TTransportException;
import com.facebook.drift.transport.MethodMetadata;
import com.facebook.drift.transport.ParameterMetadata;
import com.facebook.drift.transport.client.DriftApplicationException;
import com.facebook.drift.transport.client.MessageTooLargeException;
import com.facebook.drift.transport.client.RequestTimeoutException;
import com.facebook.drift.transport.netty.codec.FrameInfo;
import com.facebook.drift.transport.netty.codec.FrameTooLargeException;
import com.facebook.drift.transport.netty.codec.Protocol;
import com.facebook.drift.transport.netty.codec.ThriftFrame;
import com.facebook.drift.transport.netty.codec.Transport;
import com.facebook.drift.transport.netty.ssl.TChannelBufferInputTransport;
import com.facebook.drift.transport.netty.ssl.TChannelBufferOutputTransport;
import com.facebook.drift.transport.netty.throttle.ThrottleLock;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractFuture;
import io.airlift.units.Duration;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import javax.annotation.concurrent.ThreadSafe;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.drift.TApplicationException.Type.BAD_SEQUENCE_ID;
import static com.facebook.drift.TApplicationException.Type.INVALID_MESSAGE_TYPE;
import static com.facebook.drift.TApplicationException.Type.MISSING_RESULT;
import static com.facebook.drift.TApplicationException.Type.WRONG_METHOD_NAME;
import static com.facebook.drift.protocol.TMessageType.CALL;
import static com.facebook.drift.protocol.TMessageType.EXCEPTION;
import static com.facebook.drift.protocol.TMessageType.ONEWAY;
import static com.facebook.drift.protocol.TMessageType.REPLY;
import static com.facebook.drift.transport.netty.client.ConnectionFactory.THROTTLE_LOCK_KEY;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@ThreadSafe
public class ThriftClientHandler
        extends ChannelDuplexHandler
{
    private static final int ONEWAY_SEQUENCE_ID = 0xFFFF_FFFF;
    private static final Logger log = Logger.get(ThriftClientHandler.class);

    private final Duration requestTimeout;
    private final Transport transport;
    private final Protocol protocol;

    private final ConcurrentHashMap<Integer, RequestHandler> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicReference<TException> channelError = new AtomicReference<>();
    private static final AtomicInteger sequenceId = new AtomicInteger(42);

    ThriftClientHandler(Duration requestTimeout, Transport transport, Protocol protocol)
    {
        this.requestTimeout = requireNonNull(requestTimeout, "requestTimeout is null");
        this.transport = requireNonNull(transport, "transport is null");
        this.protocol = requireNonNull(protocol, "protocol is null");
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise)
            throws Exception
    {
        if (message instanceof ThriftRequest) {
            ThriftRequest thriftRequest = (ThriftRequest) message;
            //TODO : AGP
            log.info("write: Sending Thrift request : " + thriftRequest.getMethod().toString() + " with params " + thriftRequest.getParameters());
            sendMessage(ctx, thriftRequest, promise);
        }
        else {
            ctx.write(message, promise);
        }
    }

    private void sendMessage(ChannelHandlerContext context, ThriftRequest thriftRequest, ChannelPromise promise)
            throws Exception
    {
        // todo ONEWAY_SEQUENCE_ID is a header protocol thing... make sure this works with framed and unframed
        int sequenceId = thriftRequest.isOneway() ? ONEWAY_SEQUENCE_ID : this.sequenceId.incrementAndGet();
        RequestHandler requestHandler = new RequestHandler(thriftRequest, sequenceId);

        // register timeout
        requestHandler.registerRequestTimeout(context.executor());

        // write request
        ByteBuf requestBuffer = requestHandler.encodeRequest(context.alloc());

        // register request if we are expecting a response
        if (!thriftRequest.isOneway()) {
            if (pendingRequests.putIfAbsent(sequenceId, requestHandler) != null) {
                requestHandler.onChannelError(new TTransportException("Another request with the same sequenceId is already in progress"));
                requestBuffer.release();
                return;
            }
        }

        // if this connection is failed, immediately fail the request
        TException channelError = this.channelError.get();
        if (channelError != null) {
            log.error(channelError, "Error in Thrift channelError. Setting exception on thrift Future");
            thriftRequest.failed(channelError);
            requestBuffer.release();
            return;
        }

        try {
            ThriftFrame thriftFrame = new ThriftFrame(
                    sequenceId,
                    requestBuffer,
                    thriftRequest.getHeaders(),
                    ImmutableList.of(),
                    transport,
                    protocol,
                    true);

            log.info("Sending Thrift request : " + thriftFrame.getSequenceId()
                    + " pending=" + pendingRequests.size() + " pendingRequests=" + pendingRequests.keySet()
                    + " on channel " + context.channel().id().asShortText());
            log.info("channel " + context.channel().id().asShortText() + " is writable : " + context.channel().isWritable());
            ChannelFuture sendFuture = context.write(thriftFrame, promise);
            sendFuture.addListener(future -> messageSent(context, sendFuture, requestHandler));
        }
        catch (Throwable t) {
            onError(context, t, Optional.of(requestHandler));
            requestBuffer.release();
        }
    }

    private void messageSent(ChannelHandlerContext context, ChannelFuture future, RequestHandler requestHandler)
    {
        try {
            if (!future.isSuccess()) {
                log.error("Sending Thrift request failed " + requestHandler.getSequenceId() + " on channel " + context.channel().id().asShortText());
                onError(context, new TTransportException("Sending request failed", future.cause()), Optional.of(requestHandler));
                return;
            }

            log.info("Sent Thrift request : " + requestHandler.getSequenceId() + " pending=" + pendingRequests.size() + " on channel " + context.channel().id().asShortText());
            requestHandler.onRequestSent();
        }
        catch (Throwable t) {
            onError(context, t, Optional.of(requestHandler));
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message)
    {
        if (message instanceof ThriftFrame) {
            messageReceived(context, (ThriftFrame) message);
            return;
        }
        context.fireChannelRead(message);
    }

    private void messageReceived(ChannelHandlerContext context, ThriftFrame thriftFrame)
    {
        RequestHandler requestHandler = null;
        try {
            requestHandler = pendingRequests.remove(thriftFrame.getSequenceId());
            if (requestHandler == null) {
                throw new TTransportException("Unknown sequence id in response: " + thriftFrame.getSequenceId());
            }

            log.info("Received Thrift request : " + thriftFrame.getSequenceId() + " response, pending=" + pendingRequests.size() + " on channel " + context.channel().id().asShortText());
            requestHandler.onResponseReceived(thriftFrame.retain());
        }
        catch (Throwable t) {
            onError(context, t, Optional.ofNullable(requestHandler));
        }
        finally {
            thriftFrame.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause)
    {
        onError(context, cause, Optional.empty());
    }

    @Override
    public void channelInactive(ChannelHandlerContext context)
    {
        log.info("channelInactive : Closing channel " + context.channel().id().asShortText());
        onError(context, new TTransportException("Client was disconnected by server"), Optional.empty());
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx)
            throws Exception
    {
        Channel channel = ctx.channel();
        log.info("channelWritabilityChanged : Channel " + channel.id().asShortText() + " isWritable : " + channel.isWritable());
        ThrottleLock throttleLock = channel.attr(THROTTLE_LOCK_KEY).get();
        if (channel.isWritable()) {
            synchronized (throttleLock) {
                log.info("channelWritabilityChanged : Unlocking Channel " + channel.id().asShortText());
                throttleLock.notifyAll();
            }
        }
        ctx.fireChannelWritabilityChanged();
    }

    private void onError(ChannelHandlerContext context, Throwable throwable, Optional<RequestHandler> currentRequest)
    {
        if (throwable instanceof FrameTooLargeException) {
            checkArgument(!currentRequest.isPresent(), "current request should not be set for FrameTooLargeException");
            onFrameTooLargeException(context, (FrameTooLargeException) throwable);
            return;
        }
        if (throwable instanceof DriftApplicationException) {
            currentRequest.ifPresent(request -> {
                log.info("Just removing the seq ID " + request.getSequenceId() + " from the pending requests ");
                pendingRequests.remove(request.getSequenceId());
            });
            return;
        }

        TException thriftException;
        if (throwable instanceof TException) {
            thriftException = (TException) throwable;
        }
        else {
            thriftException = new TTransportException(throwable);
        }

        // set channel error
        if (!channelError.compareAndSet(null, thriftException)) {
            // another thread is already tearing down this channel
            return;
        }

        // current request may have already been removed from pendingRequests, so notify it directly
        currentRequest.ifPresent(request -> {
            pendingRequests.remove(request.getSequenceId());
            request.onChannelError(thriftException);
        });

        // notify all pending requests of the error
        // Note while loop should not be necessary since this class should be single
        // threaded, but it is better to be safe in cleanup code
        while (!pendingRequests.isEmpty()) {
            pendingRequests.values().removeIf(request -> {
                request.onChannelError(thriftException);
                return true;
            });
        }

        context.close();
    }

    private void onFrameTooLargeException(ChannelHandlerContext context, FrameTooLargeException frameTooLargeException)
    {
        TException thriftException = new MessageTooLargeException(frameTooLargeException.getMessage(), frameTooLargeException);
        Optional<FrameInfo> frameInfo = frameTooLargeException.getFrameInfo();
        if (frameInfo.isPresent()) {
            RequestHandler request = pendingRequests.remove(frameInfo.get().getSequenceId());
            if (request != null) {
                request.onChannelError(thriftException);
                return;
            }
        }
        // if sequence id is missing - fail all requests on a give channel
        onError(context, new MessageTooLargeException("unexpected too large response happened on communication channel", frameTooLargeException), Optional.empty());
    }

    public static class ThriftRequest
            extends AbstractFuture<Object>
    {
        private final MethodMetadata method;
        private final List<Object> parameters;
        private final Map<String, String> headers;

        public ThriftRequest(MethodMetadata method, List<Object> parameters, Map<String, String> headers)
        {
            this.method = method;
            this.parameters = parameters;
            this.headers = headers;
        }

        MethodMetadata getMethod()
        {
            return method;
        }

        List<Object> getParameters()
        {
            return parameters;
        }

        public Map<String, String> getHeaders()
        {
            return headers;
        }

        boolean isOneway()
        {
            return method.isOneway();
        }

        void setResponse(Object response)
        {
            set(response);
        }

        void failed(Throwable throwable)
        {
            setException(throwable);
        }
    }

    private final class RequestHandler
    {
        private final ThriftRequest thriftRequest;
        private final int sequenceId;

        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        public RequestHandler(ThriftRequest thriftRequest, int sequenceId)
        {
            this.thriftRequest = thriftRequest;
            this.sequenceId = sequenceId;
        }

        public int getSequenceId()
        {
            return sequenceId;
        }

        void registerRequestTimeout(EventExecutor executor)
        {
            try {
                timeout.set(executor.schedule(
                        () -> onChannelError(new RequestTimeoutException("Timed out waiting " + requestTimeout + " to receive response")),
                        requestTimeout.toMillis(),
                        MILLISECONDS));
            }
            catch (Throwable throwable) {
                onChannelError(new TTransportException("Unable to schedule request timeout", throwable));
                throw throwable;
            }
        }

        ByteBuf encodeRequest(ByteBufAllocator allocator)
                throws Exception
        {
            log.info("encodeRequest");
            TChannelBufferOutputTransport transport = new TChannelBufferOutputTransport(allocator);
            try {
                TProtocolWriter protocolWriter = protocol.createProtocol(transport);

                // Note that though setting message type to ONEWAY can be helpful when looking at packet
                // captures, some clients always send CALL and so servers are forced to rely on the "oneway"
                // attribute on thrift method in the interface definition, rather than checking the message
                // type.
                MethodMetadata method = thriftRequest.getMethod();
                protocolWriter.writeMessageBegin(new TMessage(method.getName(), method.isOneway() ? ONEWAY : CALL, sequenceId));

                // write the parameters
                ProtocolWriter writer = new ProtocolWriter(protocolWriter);
                writer.writeStructBegin(method.getName() + "_args");
                List<Object> parameters = thriftRequest.getParameters();
                for (int i = 0; i < parameters.size(); i++) {
                    Object value = parameters.get(i);
                    ParameterMetadata parameter = method.getParameters().get(i);
                    writer.writeField(parameter.getName(), parameter.getFieldId(), parameter.getCodec(), value);
                }
                writer.writeStructEnd();

                protocolWriter.writeMessageEnd();
                return transport.getBuffer();
            }
            catch (Throwable throwable) {
                onChannelError(throwable);
                throw throwable;
            }
            finally {
                transport.release();
            }
        }

        void onRequestSent()
        {
            log.info("onRequestSent");
            if (!thriftRequest.isOneway()) {
                return;
            }

            if (!finished.compareAndSet(false, true)) {
                log.info("Returning now since finished : onRequestSent");
                return;
            }

            try {
                cancelRequestTimeout();
                thriftRequest.setResponse(null);
            }
            catch (Throwable throwable) {
                onChannelError(throwable);
            }
        }

        void onResponseReceived(ThriftFrame thriftFrame)
        {
            log.info("onResponseReceived");
            try {
                if (!finished.compareAndSet(false, true)) {
                    log.info("Returning now since finished : onResponseReceived");
                    return;
                }

                cancelRequestTimeout();
                Object response = decodeResponse(thriftFrame.getMessage());
                log.info("Setting response on the thrift Request");
                thriftRequest.setResponse(response);
            }
            catch (Throwable throwable) {
                log.error(throwable, "Error in Thrift onResponseReceived. Setting exception on thrift Future");
                thriftRequest.failed(throwable);
            }
            finally {
                thriftFrame.release();
            }
        }

        Object decodeResponse(ByteBuf responseMessage)
                throws Exception
        {
            log.info("decodeResponse");
            TChannelBufferInputTransport transport = new TChannelBufferInputTransport(responseMessage);
            try {
                TProtocolReader protocolReader = protocol.createProtocol(transport);
                MethodMetadata method = thriftRequest.getMethod();

                // validate response header
                TMessage message = protocolReader.readMessageBegin();
                log.info("Received Message " + message.getName() + " of type " + message.getType() + " for Seq id : " + message.getSequenceId());
                if (message.getType() == EXCEPTION) {
                    TApplicationException exception = ExceptionReader.readTApplicationException(protocolReader);
                    protocolReader.readMessageEnd();
                    throw exception;
                }
                if (message.getType() != REPLY) {
                    throw new TApplicationException(INVALID_MESSAGE_TYPE, format("Received invalid message type %s from server", message.getType()));
                }
                if (!message.getName().equals(method.getName())) {
                    throw new TApplicationException(WRONG_METHOD_NAME, format("Wrong method name in reply: expected %s but received %s", method.getName(), message.getName()));
                }
                if (message.getSequenceId() != sequenceId) {
                    throw new TApplicationException(BAD_SEQUENCE_ID, format("%s failed: out of sequence response", method.getName()));
                }

                // read response struct
                ProtocolReader reader = new ProtocolReader(protocolReader);
                reader.readStructBegin();

                Object results = null;
                Exception exception = null;
                while (reader.nextField()) {
                    if (reader.getFieldId() == 0) {
                        results = reader.readField(method.getResultCodec());
                    }
                    else {
                        ThriftCodec<Object> exceptionCodec = method.getExceptionCodecs().get(reader.getFieldId());
                        if (exceptionCodec != null) {
                            exception = (Exception) reader.readField(exceptionCodec);
                        }
                        else {
                            reader.skipFieldData();
                        }
                    }
                }
                reader.readStructEnd();
                protocolReader.readMessageEnd();

                if (exception != null) {
                    log.info("Throwing DriftApplicationException");
                    throw new DriftApplicationException(exception);
                }

                if (method.getResultCodec().getType() == ThriftType.VOID) {
                    return null;
                }

                if (results == null) {
                    throw new TApplicationException(MISSING_RESULT, format("%s failed: unknown result", method.getName()));
                }
                return results;
            }
            finally {
                transport.release();
            }
        }

        void onChannelError(Throwable requestException)
        {
            log.info("onChannelError");
            if (!finished.compareAndSet(false, true)) {
                log.info("Returning now since finished : onChannelError");
                return;
            }

            try {
                cancelRequestTimeout();
            }
            finally {
                log.error(requestException, "Error in Thrift onChannelError. Setting exception on thrift Future");
                thriftRequest.failed(requestException);
            }
        }

        private void cancelRequestTimeout()
        {
            ScheduledFuture<?> timeout = this.timeout.get();
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }
}
