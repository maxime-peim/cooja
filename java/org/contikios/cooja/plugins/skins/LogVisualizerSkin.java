/*
 * Copyright (c) 2009, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 */

package org.contikios.cooja.plugins.skins;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.util.Observable;
import java.util.Observer;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Mote;
import org.contikios.cooja.MoteInterface;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.SimEventCentral.LogOutputEvent;
import org.contikios.cooja.SimEventCentral.LogOutputListener;
import org.contikios.cooja.SimEventCentral.MoteCountListener;
import org.contikios.cooja.interfaces.Log;
import org.contikios.cooja.interfaces.Position;
import org.contikios.cooja.interfaces.SerialPort;
import org.contikios.cooja.plugins.Visualizer;
import org.contikios.cooja.plugins.VisualizerSkin;

/**
 * Visualizer skin for Log output.
 *
 * Paints the last log message above each mote.
 *
 * @author Fredrik Osterlind
 */
@ClassDescription("Log output: printf()'s")
public class LogVisualizerSkin implements VisualizerSkin {
  private static final Logger logger = LogManager.getLogger(LogVisualizerSkin.class);

  private Simulation simulation = null;
  private Visualizer visualizer = null;

  private LogOutputListener logOutputListener = new LogOutputListener() {
    @Override
    public void moteWasAdded(Mote mote) {
      visualizer.repaint();
    }
    @Override
    public void moteWasRemoved(Mote mote) {
      visualizer.repaint();
    }
    @Override
    public void newLogOutput(LogOutputEvent ev) {
      visualizer.repaint();
    }
    @Override
    public void removedLogOutput(LogOutputEvent ev) {
    }
  };

  @Override
  public void setActive(Simulation simulation, Visualizer vis) {
    this.simulation = simulation;
    this.visualizer = vis;

    simulation.getEventCentral().addLogOutputListener(logOutputListener);
  }

  @Override
  public void setInactive() {
    simulation.getEventCentral().removeLogOutputListener(logOutputListener);
  }

  @Override
  public Color[] getColorOf(Mote mote) {
    return null;
  }

  @Override
  public void paintBeforeMotes(Graphics g) {
  }

  @Override
  public void paintAfterMotes(Graphics g) {
    FontMetrics fm = g.getFontMetrics();
    g.setColor(Color.BLACK);

    /* Paint last output below motes */
    Mote[] allMotes = simulation.getMotes();
    for (Mote mote: allMotes) {
      String msg = null;
      for (MoteInterface mi: mote.getInterfaces().getInterfaces()) {
        if (!(mi instanceof Log)) {
          continue;
        }
        Log log = (Log) mi;
        if (log.getLastLogMessage() == null) {
          continue;
        }
        msg = log.getLastLogMessage();
      }
      if (msg == null) {
        continue;
      }

      Position pos = mote.getInterfaces().getPosition();
      Point pixel = visualizer.transformPositionToPixel(pos);

      int msgWidth = fm.stringWidth(msg);
      g.drawString(msg, pixel.x - msgWidth/2, pixel.y - Visualizer.MOTE_RADIUS);
    }
  }

  @Override
  public Visualizer getVisualizer() {
    return visualizer;
  }
}
