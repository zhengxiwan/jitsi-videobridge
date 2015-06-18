/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

package org.jitsi.videobridge.transform;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.impl.neomedia.transform.rtcp.*;
import org.jitsi.service.neomedia.rtp.*;

/**
 * A <tt>TransformEngine</tt> implementation which parses RTCP packets and
 * transforms them using a transformer for <tt>RTCPCompoundPacket</tt>s.
 * This is similar to (and based on) <tt>libjitsi</tt>'s
 * <tt>RTCPTerminationTransformEngine</tt> but is not connected with a
 * <tt>MediaStream</tt>.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class RTCPTransformEngine
    extends SingleRTCPPacketTransformer
    implements TransformEngine
{
    /**
     * The chain of transformers that operate on <tt>RTCPCompoundPacket</tt>s
     * (already parsed).
     */
    private RTCPPacketTransformer[] chain;

    /**
     * Initializes this transformer with the given chain of transformers.
     * @param chain
     */
    public RTCPTransformEngine(RTCPPacketTransformer[] chain)
    {
        this.chain = chain;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.rtp.RTCPPacketTransformer#transform(RTCPCompoundPacket)}.
     *
     * Does not touch outgoing packets.
     */
    @Override
    public RTCPCompoundPacket transform(RTCPCompoundPacket rtcpCompoundPacket)
    {
        // Not implemented.
        return rtcpCompoundPacket;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.rtp.RTCPPacketTransformer#reverseTransform(RTCPCompoundPacket)}.
     *
     * Transforms incoming RTCP packets through the configured transformer
     * chain.
     */
    @Override
    public RTCPCompoundPacket reverseTransform(
        RTCPCompoundPacket inPacket)
    {
        if (chain != null)
        {
            for (RTCPPacketTransformer transformer : chain)
            {
                if (transformer != null)
                    inPacket = transformer.reverseTransform(inPacket);
            }
        }

        return inPacket;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.rtp.RTCPPacketTransformer#close()}.
     */
    @Override
    public void close()
    {
    }

    /**
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.TransformEngine#getRTPTransformer()}.
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return null;
    }

    /**
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.TransformEngine#getRTCPTransformer()}.
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return this;
    }
}
