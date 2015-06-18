/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast;

/**
 * @author George Politis
 */
public class BridgeSimulcastSender
    extends BasicSimulcastSender
{
    public BridgeSimulcastSender(SimulcastManager mySM)
    {
        this.mySM = mySM;
    }

    private final SimulcastManager mySM;

    public SimulcastManager getSimulcastManager()
    {
        return this.mySM;
    }
}
