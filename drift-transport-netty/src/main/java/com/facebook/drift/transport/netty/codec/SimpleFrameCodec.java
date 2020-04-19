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
package com.facebook.drift.transport.netty.codec;

import com.facebook.airlift.log.Logger;
import com.facebook.drift.protocol.TMessage;
import com.facebook.drift.protocol.TTransportException;
import com.facebook.drift.transport.netty.ssl.TChannelBufferInputTransport;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import static java.util.Objects.requireNonNull;

public class SimpleFrameCodec
        extends ChannelDuplexHandler
{
    private static final Logger log = Logger.get(SimpleFrameCodec.class);
    private final Transport transport;
    private final Protocol protocol;
    private final boolean assumeClientsSupportOutOfOrderResponses;

    public SimpleFrameCodec(Transport transport, Protocol protocol, boolean assumeClientsSupportOutOfOrderResponses)
    {
        this.transport = requireNonNull(transport, "transport is null");
        this.protocol = requireNonNull(protocol, "protocol is null");
        this.assumeClientsSupportOutOfOrderResponses = assumeClientsSupportOutOfOrderResponses;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message)
            throws Exception
    {
        if (message instanceof ByteBuf) {
            ByteBuf buffer = (ByteBuf) message;
            if (buffer.isReadable()) {
                context.fireChannelRead(new ThriftFrame(
                        extractResponseSequenceId(buffer.retain()),
                        buffer,
                        ImmutableMap.of(),
                        ImmutableList.of(),
                        transport,
                        protocol,
                        assumeClientsSupportOutOfOrderResponses));
                return;
            }
        }
        context.fireChannelRead(message);
    }

    private int extractResponseSequenceId(ByteBuf buffer)
            throws TTransportException
    {
        TChannelBufferInputTransport inputTransport = new TChannelBufferInputTransport(buffer.duplicate());
        try {
            TMessage message = protocol.createProtocol(inputTransport).readMessageBegin();
            return message.getSequenceId();
        }
        catch (Throwable e) {
            throw new TTransportException("Could not find sequenceId in Thrift message", e);
        }
        finally {
            inputTransport.release();
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
    {
        if (message instanceof ThriftFrame) {
            // strip the underlying message from the frame
            ThriftFrame thriftFrame = (ThriftFrame) message;
            try {
                // Note: simple transports do not support headers. This is acceptable since headers should be inconsequential to the request
                log.info("Writing Thrift Message " + thriftFrame.getSequenceId() + " on channel " + context.channel().id().asShortText());
                message = thriftFrame.getMessage();
            }
            finally {
                thriftFrame.release();
            }
        }
        context.write(message, promise);
    }
}
