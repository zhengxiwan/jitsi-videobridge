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
package org.jitsi.videobridge;

import org.jitsi.util.*;
import org.jitsi.videobridge.transform.*;

import java.util.concurrent.*;

/**
 * A class that uses RTX to send padding data to probe for available bandwidth.
 *
 * FIXME This needs to be moved down to LJ, along with all the RTX stuff.
 *
 * @author George Politis
 */
public class Prober
{
    /**
     *
     */
    private static final Logger logger = Logger.getLogger(Prober.class);

    /**
     * The period during which we probe.
     */
    private final static int WINDOW_MS = 500;

    /**
     * The rate we sent the probes.
     */
    private final static int RATE_MS = 33;

    /**
     * The {@link VideoChannel} the owns this instance.
     */
    private final VideoChannel vc;

    /**
     * The maximum transmission unit (MTU) to be assumed by
     * {@code Prober}.
     */
    private static final int MTU = 1024 + 256;

    /**
     *
     */
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(1);

    /**
     *
     */
    private ScheduledFuture<?> scheduledFuture;

    /**
     *
     */
    private long startMillis = -1;

    /**
     * Ctor.
     *
     * @param vc
     */
    public Prober(VideoChannel vc)
    {
        this.vc = vc;
    }

    /**
     *
     * @param targetBitrateBps
     */
    public void probe(long targetBitrateBps)
    {
        stop(); // make sure any previous scheduled tasks are canceled.

        // Remember when we started probing.
        startMillis = System.currentTimeMillis();

        // Calculate the padding bitrate from the target bitrate.
        long paddingBitrateBps = targetBitrateBps
            - vc.getStream().getMediaStreamStats().getSendingBitrate();

        // Calculate how many bytes to send at each probing interval.
        final long bytes = (paddingBitrateBps * RATE_MS) / 8 / 1000;

        logger.info("Probing bytes=" + bytes
            + ", rate=" + RATE_MS + ", period=" + WINDOW_MS);

        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                // Don't probe for more than WINDOW_MS millis.
                if (System.currentTimeMillis() - startMillis > WINDOW_MS)
                {
                    scheduledFuture.cancel(false);
                    return;
                }

                int cntFatPackets = (int) (bytes / MTU);
                int szNormalPacket = (int) (bytes % MTU);

                RtxTransformer rtxTransformer
                    = vc.getTransformEngine().getRtxTransformer();

                for (int i = 0; i < cntFatPackets - 1; i++)
                {
                    rtxTransformer.transmitPadding(MTU);
                }

                rtxTransformer.transmitPadding(szNormalPacket);
            }
        };

        this.scheduledFuture = scheduler.scheduleAtFixedRate(
            r, 0 , RATE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     *
     */
    public void stop()
    {
        if (scheduledFuture != null)
        {
            scheduledFuture.cancel(false);
        }
    }
}
