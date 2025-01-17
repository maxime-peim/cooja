/*
 * Copyright (c) 2009, Swedish Institute of Computer Science. All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer. 2. Redistributions in
 * binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution. 3. Neither the name of the
 * Institute nor the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package org.contikios.cooja;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Observable;
import java.util.Observer;
import java.util.Random;
import java.util.Vector;

import javax.swing.JOptionPane;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.jdom.Element;

import org.contikios.cooja.dialogs.CreateSimDialog;

/**
 * A simulation consists of a number of motes and mote types.
 *
 * A simulation is observable:
 * changed simulation state, added or deleted motes etc are observed.
 * To track mote changes, observe the mote (interfaces) itself.
 *
 * @author Fredrik Osterlind
 */
public class Simulation extends Observable implements Runnable {
  public static final long MICROSECOND = 1L;
  public static final long MILLISECOND = 1000*MICROSECOND;

  /*private static long EVENT_COUNTER = 0;*/

  private Vector<Mote> motes = new Vector<Mote>();
  private Vector<Mote> motesUninit = new Vector<Mote>();
  
  private Vector<MoteType> moteTypes = new Vector<MoteType>();

  /* If true, run simulation at full speed */
  private boolean speedLimitNone = true;
  /* Limit simulation speed to maxSpeed; if maxSpeed is 1.0 simulation is run at real-time speed */
  private double speedLimit;
  /* Used to restrict simulation speed */
  private long speedLimitLastSimtime;
  private long speedLimitLastRealtime;

  private long lastStartTime;
  private long currentSimulationTime = 0;

  private String title = null;

  private RadioMedium currentRadioMedium = null;

  private static final Logger logger = LogManager.getLogger(Simulation.class);

  private boolean isRunning = false;

  private boolean stopSimulation = false;

  private Thread simulationThread = null;

  private Cooja cooja = null;

  private long randomSeed = 123456;

  private boolean randomSeedGenerated = false;

  private long maxMoteStartupDelay = 1000*MILLISECOND;

  private SafeRandom randomGenerator;

  private boolean hasMillisecondObservers = false;
  private MillisecondObservable millisecondObservable = new MillisecondObservable();
  private class MillisecondObservable extends Observable {
    private void newMillisecond(long time) {
      setChanged();
      notifyObservers(time);
    }
  }

  /* Event queue */
  private EventQueue eventQueue = new EventQueue();

  /* Poll requests */
  private boolean hasPollRequests = false;
  private ArrayDeque<Runnable> pollRequests = new ArrayDeque<Runnable>();


  /**
   * Request poll from simulation thread.
   * Poll requests are prioritized over simulation events, and are
   * executed between each simulation event.
   *
   * @param r Simulation thread action
   */
  public void invokeSimulationThread(Runnable r) {
    synchronized (pollRequests) {
      pollRequests.addLast(r);
      hasPollRequests = true;
    }
  }

  private Runnable popSimulationInvokes() {
    Runnable r;
    synchronized (pollRequests) {
      r = pollRequests.pop();
      hasPollRequests = !pollRequests.isEmpty();
    }
    return r;
  }

  /**
   * Add millisecond observer.
   * This observer is notified once every simulated millisecond.
   *
   * @see #deleteMillisecondObserver(Observer)
   * @param newObserver Observer
   */
  public void addMillisecondObserver(Observer newObserver) {
    millisecondObservable.addObserver(newObserver);
    hasMillisecondObservers = true;

    invokeSimulationThread(new Runnable() {
      @Override
      public void run() {
        if (!millisecondEvent.isScheduled()) {
          scheduleEvent(
              millisecondEvent,
              currentSimulationTime - (currentSimulationTime % MILLISECOND) + MILLISECOND);
        }
      }
    });
  }

  /**
   * Delete millisecond observer.
   *
   * @see #addMillisecondObserver(Observer)
   * @param observer Observer to delete
   */
  public void deleteMillisecondObserver(Observer observer) {
    millisecondObservable.deleteObserver(observer);
    hasMillisecondObservers = millisecondObservable.countObservers() > 0;
  }

  /**
   * @return True iff current thread is the simulation thread
   */
  public boolean isSimulationThread() {
    return simulationThread == Thread.currentThread();
  }

  /**
   * @return True iff current thread is the simulation thread,
   * or the simulation threat has not yet been created.
   */
  public boolean isSimulationThreadOrNull() {
    return simulationThread == Thread.currentThread() || simulationThread == null;
  }

  /**
   * Schedule simulation event for given time.
   * Already scheduled events must be removed before they are rescheduled.
   *
   * If the simulation is running, this method may only be called from the simulation thread.
   *
   * @see #invokeSimulationThread(Runnable)
   *
   * @param e Event
   * @param time Execution time
   */
  public void scheduleEvent(final TimeEvent e, final long time) {
    if (isRunning) {
      /* TODO Strict scheduling from simulation thread */
      assert isSimulationThread() : "Scheduling event from non-simulation thread: " + e;
    }
    eventQueue.addEvent(e, time);
  }

  private final TimeEvent delayEvent = new TimeEvent() {
    @Override
    public void execute(long t) {
      if (speedLimitNone) {
        /* As fast as possible: no need to reschedule delay event */
        return;
      }

      long diffSimtime = (getSimulationTime() - speedLimitLastSimtime)/1000; /* ms */
      long diffRealtime = System.currentTimeMillis() - speedLimitLastRealtime; /* ms */
      long expectedDiffRealtime = (long) (diffSimtime/speedLimit);
      long sleep = expectedDiffRealtime - diffRealtime;
      if (sleep >= 0) {
        /* Slow down simulation */
        try {
          Thread.sleep(sleep);
        } catch (InterruptedException e) {
        }
        scheduleEvent(this, t+MILLISECOND);
      } else {
        /* Reduce slow-down: execute this delay event less often */
        scheduleEvent(this, t-sleep*MILLISECOND);
      }

      /* Update counters every second */
      if (diffRealtime > 1000) {
        speedLimitLastRealtime = System.currentTimeMillis();
        speedLimitLastSimtime = getSimulationTime();
      }
    }
    @Override
    public String toString() {
      return "DELAY";
    }
  };

  private final TimeEvent millisecondEvent = new TimeEvent() {
    @Override
    public void execute(long t) {
      if (!hasMillisecondObservers) {
        return;
      }

      millisecondObservable.newMillisecond(getSimulationTime());
      scheduleEvent(this, t+MILLISECOND);
    }
    @Override
    public String toString() {
      return "MILLISECOND: " + millisecondObservable.countObservers();
    }
  };

  public void clearEvents() {
    eventQueue.clear();
    pollRequests.clear();
  }

  @Override
  public void run() {
    lastStartTime = System.currentTimeMillis();
    logger.debug("Simulation started, system time: " + lastStartTime);
    isRunning = true;
    speedLimitLastRealtime = System.currentTimeMillis();
    speedLimitLastSimtime = getSimulationTime();

    /* Simulation starting */
    this.setChanged();
    this.notifyObservers(this);

    EventQueue.Pair nextEvent = null;
    try {
      while (isRunning) {

        /* Handle all poll requests */
        while (hasPollRequests) {
          popSimulationInvokes().run();
        }

        /* Handle one simulation event, and update simulation time */
        nextEvent = eventQueue.popFirst();
        if (nextEvent == null) {
          throw new RuntimeException("No more events");
        }
        if (nextEvent.time < currentSimulationTime) {
          throw new RuntimeException("Next event is in the past: " + nextEvent.time + " < " + currentSimulationTime + ": " + nextEvent);
        }
        currentSimulationTime = nextEvent.time;
        /*logger.info("Executing event #" + EVENT_COUNTER++ + " @ " + currentSimulationTime + ": " + nextEvent);*/
        nextEvent.event.execute(currentSimulationTime);

        if (stopSimulation) {
          isRunning = false;
        }
      }
    } catch (RuntimeException e) {
    	if ("MSPSim requested simulation stop".equals(e.getMessage())) {
    		/* XXX Should be*/
    		logger.info("Simulation stopped due to MSPSim breakpoint");
    	} else {

    		logger.fatal("Simulation stopped due to error: " + e.getMessage(), e);
    		if (!Cooja.isVisualized()) {
    			/* Quit simulator if in test mode */
    			System.exit(1);
    		} else {
    		  String title = "Simulation error";
    		  if (nextEvent.event instanceof MoteTimeEvent) {
    		    title += ": " + ((MoteTimeEvent)nextEvent.event).getMote();
    		  }
    		  Cooja.showErrorDialog(Cooja.getTopParentContainer(), title, e, false);
    		}
    	}
    }
    isRunning = false;
    simulationThread = null;
    stopSimulation = false;

    this.setChanged();
    this.notifyObservers(this);
    logger.info("Simulation completed, system time: " + System.currentTimeMillis() +
        "\tDuration: " + (System.currentTimeMillis() - lastStartTime) +
                " ms" +
                "\tSimulated time " + getSimulationTimeMillis() +
                " ms\tRatio " +
                ((double)getSimulationTimeMillis() /
                 (double)(System.currentTimeMillis() - lastStartTime)));
  }

  /**
   * Creates a new simulation
   */
  public Simulation(Cooja cooja) {
    this.cooja = cooja;
    randomGenerator = new SafeRandom(this);
  }

  /**
   * Starts this simulation (notifies observers).
   */
  public void startSimulation() {
    if (!isRunning()) {
      isRunning = true;
      simulationThread = new Thread(this);
      simulationThread.setPriority(Thread.MIN_PRIORITY);
      simulationThread.start();
    }
  }

  /**
   * Stop simulation
   *
   * @param block Block until simulation has stopped, with timeout (100ms)
   *
   * @see #stopSimulation()
   */
  public void stopSimulation(boolean block) {
    if (!isRunning()) {
      return;
    }
    stopSimulation = true;

    if (block) {
      if (Thread.currentThread() == simulationThread) {
        return;
      }

      /* Wait until simulation stops */
      try {
        Thread simThread = simulationThread;
        if (simThread != null) {
          simThread.join(100);
        }
      } catch (InterruptedException e) {
      }
    }
  }

  /**
   * Stop simulation (blocks).
   * Calls stopSimulation(true).
   *
   * @see #stopSimulation(boolean)
   */
  public void stopSimulation() {
    stopSimulation(true);
  }

  /**
   * Starts simulation if stopped, executes one millisecond, and finally stops
   * simulation again.
   */
  public void stepMillisecondSimulation() {
    if (isRunning()) {
      return;
    }
    TimeEvent stopEvent = new TimeEvent() {
      @Override
      public void execute(long t) {
        /* Stop simulation */
        stopSimulation();
      }
    };
    scheduleEvent(stopEvent, getSimulationTime()+Simulation.MILLISECOND);
    startSimulation();
  }

  public Cooja getCooja() {
    return cooja;
  }

  /**
   * @return Random seed
   */
  public long getRandomSeed() {
    return randomSeed;
  }

  /**
   * @return Random seed (converted to a string)
   */
  public String getRandomSeedString() {
    return Long.toString(randomSeed);
  }

  /**
   * @param randomSeed Random seed
   */
  public void setRandomSeed(long randomSeed) {
    this.randomSeed = randomSeed;
    randomGenerator.setSeed(randomSeed);
    String name =
      cooja.currentConfigFile == null ? "(unnamed)"
                                      : cooja.currentConfigFile.toString();
    logger.info("Simulation " + name + " random seed: " + randomSeed);
  }

  /**
   * @param generated Autogenerated random seed at simulation load
   */
  public void setRandomSeedGenerated(boolean generated) {
    this.randomSeedGenerated = generated;
  }

  /**
   * @return Autogenerated random seed at simulation load
   */
  public boolean getRandomSeedGenerated() {
    return randomSeedGenerated;
  }

  public Random getRandomGenerator() {
    return randomGenerator;
  }

  /**
   * @return Maximum mote startup delay
   */
  public long getDelayedMoteStartupTime() {
    return maxMoteStartupDelay;
  }

  /**
   * @param maxMoteStartupDelay Maximum mote startup delay
   */
  public void setDelayedMoteStartupTime(long maxMoteStartupDelay) {
    this.maxMoteStartupDelay = Math.max(0, maxMoteStartupDelay);
  }

  private SimEventCentral eventCentral = new SimEventCentral(this);
  public SimEventCentral getEventCentral() {
    return eventCentral;
  }

  /**
   * Returns the current simulation config represented by XML elements. This
   * config also includes the current radio medium, all mote types and motes.
   *
   * @return Current simulation config
   */
  public Collection<Element> getConfigXML() {
    ArrayList<Element> config = new ArrayList<Element>();

    Element element;

    // Title
    element = new Element("title");
    element.setText(title);
    config.add(element);

    /* Max simulation speed */
    if (!speedLimitNone) {
      element = new Element("speedlimit");
      element.setText("" + getSpeedLimit());
      config.add(element);
    }

    // Random seed
    element = new Element("randomseed");
    if (randomSeedGenerated) {
      element.setText("generated");
    } else {
      element.setText(Long.toString(getRandomSeed()));
    }
    config.add(element);

    // Max mote startup delay
    element = new Element("motedelay_us");
    element.setText(Long.toString(maxMoteStartupDelay));
    config.add(element);

    // Radio Medium
    element = new Element("radiomedium");
    element.setText(currentRadioMedium.getClass().getName());

    Collection<Element> radioMediumXML = currentRadioMedium.getConfigXML();
    if (radioMediumXML != null) {
      element.addContent(radioMediumXML);
    }
    config.add(element);

    /* Event central */
    element = new Element("events");
    element.addContent(eventCentral.getConfigXML());
    config.add(element);

    // Mote types
    for (MoteType moteType : getMoteTypes()) {
      element = new Element("motetype");
      element.setText(moteType.getClass().getName());

      Collection<Element> moteTypeXML = moteType.getConfigXML(this);
      if (moteTypeXML != null) {
        element.addContent(moteTypeXML);
      }
      config.add(element);
    }

    // Motes
    for (Mote mote : motes) {
      element = new Element("mote");

      Collection<Element> moteConfig = mote.getConfigXML();
      if (moteConfig == null) {
        moteConfig = new ArrayList<Element>();
      }

      /* Add mote type identifier */
      Element typeIdentifier = new Element("motetype_identifier");
      typeIdentifier.setText(mote.getType().getIdentifier());
      moteConfig.add(typeIdentifier);

      element.addContent(moteConfig);
      config.add(element);
    }

    return config;
  }

  
  /* indicator to components setting up that they need to respect the fast setup mode */
  private boolean quick = false;
  public boolean isQuickSetup() {
      return quick;
  }
  
  /**
   * Sets the current simulation config depending on the given configuration.
   *
   * @param configXML Simulation configuration
   * @param visAvailable True if simulation is allowed to show visualizers
   * @param manualRandomSeed Simulation random seed. May be null, in which case the configuration is used
   * @return True if simulation was configured successfully
   * @throws Exception If configuration could not be loaded
   */
  public boolean setConfigXML(Collection<Element> configXML,
      boolean visAvailable, boolean quick, Long manualRandomSeed) throws Exception {
    this.quick = quick;

    // Parse elements
    for (Element element : configXML) {

      // Title
      if (element.getName().equals("title")) {
        title = element.getText();
      }

      /* Max simulation speed */
      if (element.getName().equals("speedlimit")) {
        String text = element.getText();
        if (text.equals("null")) {
          setSpeedLimit(null);
        } else {
          setSpeedLimit(Double.parseDouble(text));
        }
      }

      // Random seed
      if (element.getName().equals("randomseed")) {
        long newSeed;

        if (element.getText().equals("generated")) {
          randomSeedGenerated = true;
          newSeed = new Random().nextLong();
        } else {
          newSeed = Long.parseLong(element.getText());
        }
        if (manualRandomSeed != null) {
          newSeed = manualRandomSeed;
        }

        setRandomSeed(newSeed);
      }

      // Max mote startup delay
      if (element.getName().equals("motedelay")) {
        maxMoteStartupDelay = Integer.parseInt(element.getText())*MILLISECOND;
      }
      if (element.getName().equals("motedelay_us")) {
        maxMoteStartupDelay = Integer.parseInt(element.getText());
      }

      // Radio medium
      if (element.getName().equals("radiomedium")) {
        String radioMediumClassName = element.getText().trim();

        /* Backwards compatibility: se.sics -> org.contikios */
        if (radioMediumClassName.startsWith("se.sics")) {
        	radioMediumClassName = radioMediumClassName.replaceFirst("se\\.sics", "org.contikios");
        }

        Class<? extends RadioMedium> radioMediumClass = cooja.tryLoadClass(
            this, RadioMedium.class, radioMediumClassName);

        if (radioMediumClass != null) {
          // Create radio medium specified in config
          try {
            currentRadioMedium = RadioMedium.generateRadioMedium(radioMediumClass, this);
          } catch (Exception e) {
            currentRadioMedium = null;
            logger.warn("Could not load radio medium class: " + radioMediumClassName);
          }
        }

        // Show configure simulation dialog
        boolean createdOK = false;
        if (visAvailable && !quick) {
          createdOK = CreateSimDialog.showDialog(Cooja.getTopParentContainer(), this);
        } else {
          createdOK = true;
        }

        if (!createdOK) {
          logger.debug("Simulation not created, aborting");
          throw new Exception("Load aborted by user");
        }

        // Check if radio medium specific config should be applied
        if (radioMediumClassName.equals(currentRadioMedium.getClass().getName())) {
          currentRadioMedium.setConfigXML(element.getChildren(), visAvailable);
        } else {
          logger.info("Radio Medium changed - ignoring radio medium specific config");
        }
      }

      /* Event central */
      if (element.getName().equals("events")) {
        eventCentral.setConfigXML(this, element.getChildren(), visAvailable);
      }

      // Mote type
      if (element.getName().equals("motetype")) {
        String moteTypeClassName = element.getText().trim();

        /* Backwards compatibility: se.sics -> org.contikios */
        if (moteTypeClassName.startsWith("se.sics")) {
        	moteTypeClassName = moteTypeClassName.replaceFirst("se\\.sics", "org.contikios");
        }

        /* Try to recreate simulation using a different mote type */
        if (visAvailable && !quick) {
          String[] availableMoteTypes = getCooja().getProjectConfig().getStringArrayValue("org.contikios.cooja.Cooja.MOTETYPES");
          String newClass = (String) JOptionPane.showInputDialog(
              Cooja.getTopParentContainer(),
              "The simulation is about to load '" + moteTypeClassName + "'\n" +
              "You may try to load the simulation using a different mote type.\n",
              "Loading mote type",
              JOptionPane.QUESTION_MESSAGE,
              null,
              availableMoteTypes,
              moteTypeClassName
          );
          if (newClass == null) {
            throw new MoteType.MoteTypeCreationException("No mote type class selected");
          }
          if (!newClass.equals(moteTypeClassName)) {
            logger.warn("Changing mote type class: " + moteTypeClassName + " -> " + newClass);
            moteTypeClassName = newClass;
          }
        }

        Class<? extends MoteType> moteTypeClass = cooja.tryLoadClass(this,
            MoteType.class, moteTypeClassName);

        if (moteTypeClass == null) {
          logger.fatal("Could not load mote type class: " + moteTypeClassName);
          throw new MoteType.MoteTypeCreationException("Could not load mote type class: " + moteTypeClassName);
        }

        MoteType moteType = moteTypeClass.getConstructor((Class[]) null).newInstance();

        boolean createdOK = moteType.setConfigXML(this, element.getChildren(),
            visAvailable);
        if (createdOK) {
          addMoteType(moteType);
        } else {
          logger
              .fatal("Mote type was not created: " + element.getText().trim());
          return false;
        }
      }

      /* Mote */
      if (element.getName().equals("mote")) {

        /* Read mote type identifier */
        MoteType moteType = null;
        for (Element subElement: (Collection<Element>) element.getChildren()) {
          if (subElement.getName().equals("motetype_identifier")) {
            moteType = getMoteType(subElement.getText());
            if (moteType == null) {
              throw new Exception("No mote type '" + subElement.getText() + "' for mote");
            }
            break;
          }
        }
        if (moteType == null) {
          throw new Exception("No mote type specified for mote");
        }

        /* Create mote using mote type */
        Mote mote = moteType.generateMote(this);
        if (mote.setConfigXML(this, element.getChildren(), visAvailable)) {
        	if (getMoteWithID(mote.getID()) != null) {
        		logger.warn("Ignoring duplicate mote ID: " + mote.getID());
        	} else {
        		addMote(mote);
        	}
        } else {
          logger.fatal("Mote was not created: " + element.getText().trim());
          throw new Exception("All motes were not recreated");
        }
      }
    }

    if (currentRadioMedium != null) {
      currentRadioMedium.simulationFinishedLoading();
    }

    // Quick load mode only during loading
    this.quick = false;

    setChanged();
    notifyObservers(this);

    /* Execute simulation thread events now, before simulation starts */
    while (hasPollRequests) {
      popSimulationInvokes().run();
    }

    return true;
  }

  /**
   * Removes a mote from this simulation
   *
   * @param mote
   *          Mote to remove
   */
  public void removeMote(final Mote mote) {

    /* Simulation is running, remove mote in simulation loop */
    Runnable removeMote = new Runnable() {
      @Override
      public void run() {
        motes.remove(mote);
        motesUninit.remove(mote);
        currentRadioMedium.unregisterMote(mote, Simulation.this);

        /* Dispose mote interface resources */
        mote.removed();
        for (MoteInterface i: mote.getInterfaces().getInterfaces()) {
          i.removed();
        }

        setChanged();
        notifyObservers(mote);

        // Delete all events associated with deleted mote.
        eventQueue.removeIf(
          (TimeEvent ev) ->
            ev instanceof MoteTimeEvent && ((MoteTimeEvent)ev).getMote() == mote);
      }
    };

    if (!isRunning()) {
      /* Simulation is stopped, remove mote immediately */
      removeMote.run();
    } else {
      /* Remove mote from simulation thread */
      invokeSimulationThread(removeMote);
    }

    getCooja().closeMotePlugins(mote);
  }

  /**
   * Called to free resources used by the simulation.
   * This method is called just before the simulation is removed.
   */
  public void removed() {
  	/* Remove radio medium */
  	if (currentRadioMedium != null) {
  		currentRadioMedium.removed();
  	}

    /* Remove all motes */
    Mote[] motes = getMotes();
    for (Mote m: motes) {
      removeMote(m);
    }
  }

  /**
   * Adds a mote to this simulation
   *
   * @param mote
   *          Mote to add
   */
  public void addMote(final Mote mote) {
    Runnable addMote = new Runnable() {
      @Override
      public void run() {
        if (mote.getInterfaces().getClock() != null) {
          if (maxMoteStartupDelay > 0) {
            mote.getInterfaces().getClock().setDrift(
                - getSimulationTime()
                - randomGenerator.nextInt((int)maxMoteStartupDelay)
            );
          } else {
            mote.getInterfaces().getClock().setDrift(-getSimulationTime());
          }
        }

        motes.add(mote);
        motesUninit.remove(mote);
        currentRadioMedium.registerMote(mote, Simulation.this);

        /* Notify mote interfaces that node was added */
        for (MoteInterface i: mote.getInterfaces().getInterfaces()) {
          i.added();
        }

        setChanged();
        notifyObservers(mote);
        cooja.updateGUIComponentState();
      }
    };

    //Add to list of uninitialized motes
    motesUninit.add(mote);

    if (!isRunning()) {
      /* Simulation is stopped, add mote immediately */
      addMote.run();
    } else {
      /* Add mote from simulation thread */
      invokeSimulationThread(addMote);
    }
    
  }

  /**
   * Returns simulation mote at given list position.
   *
   * @param pos Internal list position of mote
   * @return Mote
   * @see #getMotesCount()
   * @see #getMoteWithID(int)
   */
  public Mote getMote(int pos) {
    return motes.get(pos);
  }

  /**
   * Returns simulation with with given ID.
   *
   * @param id ID
   * @return Mote or null
   * @see Mote#getID()
   */
  public Mote getMoteWithID(int id) {
    for (Mote m: motes) {
      if (m.getID() == id) {
        return m;
      }
    }
    return null;
  }

  /**
   * Returns uninitialised simulation mote with with given ID.
   * 
   * @param id ID
   * @return Mote or null
   * @see Mote#getID()
   */
  public Mote getMoteWithIDUninit(int id) {
    for (Mote m: motesUninit) {
      if (m.getID() == id) {
        return m;
      }
    }
    return null;
  }



  /**
   * Returns number of motes in this simulation.
   *
   * @return Number of motes
   */
  public int getMotesCount() {
    return motes.size();
  }

  /**
   * Returns all motes in this simulation.
   *
   * @return Motes
   */
  public Mote[] getMotes() {
    Mote[] arr = new Mote[motes.size()];
    motes.toArray(arr);
    return arr;
  }

  /**
   * Returns uninitialised motes
   *
   * @return Motes
   */
  public Mote[] getMotesUninit() {
    return motesUninit.toArray(new Mote[motesUninit.size()]);
  }


  /**
   * Returns all mote types in simulation.
   *
   * @return All mote types
   */
  public MoteType[] getMoteTypes() {
    MoteType[] types = new MoteType[moteTypes.size()];
    moteTypes.toArray(types);
    return types;
  }

  /**
   * Returns mote type with given identifier.
   *
   * @param identifier
   *          Mote type identifier
   * @return Mote type or null if not found
   */
  public MoteType getMoteType(String identifier) {
    for (MoteType moteType : getMoteTypes()) {
      if (moteType.getIdentifier().equals(identifier)) {
        return moteType;
      }
    }
    return null;
  }

  /**
   * Adds given mote type to simulation.
   *
   * @param newMoteType Mote type
   */
  public void addMoteType(MoteType newMoteType) {
    moteTypes.add(newMoteType);

    this.setChanged();
    this.notifyObservers(this);
  }

  /**
   * Remove given mote type from simulation.
   *
   * @param type Mote type
   */
  public void removeMoteType(MoteType type) {
    if (!moteTypes.contains(type)) {
      logger.fatal("Mote type is not registered: " + type);
      return;
    }

    /* Remove motes */
    for (Mote m: getMotes()) {
      if (m.getType() == type) {
        removeMote(m);
      }
    }

    moteTypes.remove(type);
    this.setChanged();
    this.notifyObservers(this);
  }

  /**
   * Limit simulation speed to given ratio.
   * This method may be called from outside the simulation thread.
   * @param newSpeedLimit
   */
  public void setSpeedLimit(final Double newSpeedLimit) {
    Runnable r = new Runnable() {
      @Override
      public void run() {
        if (newSpeedLimit == null) {
          speedLimitNone = true;
          return;
        }

        speedLimitNone = false;
        speedLimitLastRealtime = System.currentTimeMillis();
        speedLimitLastSimtime = getSimulationTime();
        speedLimit = newSpeedLimit.doubleValue();

        if (delayEvent.isScheduled()) {
          delayEvent.remove();
        }
        scheduleEvent(delayEvent, currentSimulationTime);
        Simulation.this.setChanged();
        Simulation.this.notifyObservers(this);
      }
    };
    if (!isRunning()) {
    	/* Simulation is stopped, change speed immediately */
    	r.run();
    } else {
    	/* Change speed from simulation thread */
    	invokeSimulationThread(r);
    }
  }

  /**
   * @return Max simulation speed ratio. Returns null if no limit.
   */
  public Double getSpeedLimit() {
    if (speedLimitNone) {
      return null;
    }
    return new Double(speedLimit);
  }

  /**
   * Set simulation time to simulationTime.
   *
   * @param simulationTime
   *          New simulation time (ms)
   */
  public void setSimulationTime(long simulationTime) {
    currentSimulationTime = simulationTime;

    this.setChanged();
    this.notifyObservers(this);
  }

  /**
   * Returns current simulation time.
   *
   * @return Simulation time (microseconds)
   */
  public long getSimulationTime() {
    return currentSimulationTime;
  }

  /**
   * Returns current simulation time rounded to milliseconds.
   *
   * @see #getSimulationTime()
   * @return Time rounded to milliseconds
   */
  public long getSimulationTimeMillis() {
    return currentSimulationTime / MILLISECOND;
  }

  /**
   * Return the actual time value corresponding to an argument which
   * is a simulation time value in microseconds.
   *
   * @return Actual time (microseconds)
   */
  public long convertSimTimeToActualTime(long simTime) {
    return simTime + lastStartTime * 1000;
  }

  /**
   * Changes radio medium of this simulation to the given.
   *
   * @param radioMedium
   *          New radio medium
   */
  public void setRadioMedium(RadioMedium radioMedium) {
    // Remove current radio medium from observing motes
    if (currentRadioMedium != null) {
      for (int i = 0; i < motes.size(); i++) {
        currentRadioMedium.unregisterMote(motes.get(i), this);
      }
    }

    // Change current radio medium to new one
    if (radioMedium == null) {
      logger.fatal("Radio medium could not be created.");
      return;
    }
    this.currentRadioMedium = radioMedium;

    // Add all current motes to the new radio medium
    for (int i = 0; i < motes.size(); i++) {
      currentRadioMedium.registerMote(motes.get(i), this);
    }
  }

  /**
   * Get currently used radio medium.
   *
   * @return Currently used radio medium
   */
  public RadioMedium getRadioMedium() {
    return currentRadioMedium;
  }

  /**
   * Return true is simulation is running.
   *
   * @return True if simulation is running
   */
  public boolean isRunning() {
    return isRunning && simulationThread != null;
  }

  /**
   * Return true is simulation is runnable.
   *
   * @return True if simulation is runnable
   */
  public boolean isRunnable() {
    return isRunning || hasPollRequests || !eventQueue.isEmpty();
  }

  /**
   * Get current simulation title (short description).
   *
   * @return Title
   */
  public String getTitle() {
    return title;
  }

  /**
   * Set simulation title.
   *
   * @param title
   *          New title
   */
  public void setTitle(String title) {
    this.title = title;
  }
}
