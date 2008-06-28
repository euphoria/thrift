
package com.facebook.thrift.server;

import com.facebook.thrift.TException;
import com.facebook.thrift.TProcessor;
import com.facebook.thrift.TProcessorFactory;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.protocol.TProtocolFactory;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.transport.TNonblockingServerTransport;
import com.facebook.thrift.transport.TTransport;
import com.facebook.thrift.transport.TFramedTransport;
import com.facebook.thrift.transport.TNonblockingTransport;
import com.facebook.thrift.transport.TTransportException;
import com.facebook.thrift.transport.TTransportFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import java.io.IOException;

/**
 * An extension of the TNonblockingServer to a Half-Sync/Half-Async server. 
 * Like TNonblockingServer, it relies on the use of TFramedTransport.
 */
public class THsHaServer extends TNonblockingServer {

  // This wraps all the functionality of queueing and thread pool management
  // for the passing of Invocations from the Selector to workers.
  private ExecutorService invoker;

  private Options options;

  /**
   * Create server with given processor, and server transport. Default server 
   * options, TBinaryProtocol for the protocol, and TFramedTransport.Factory on 
   * both input and output transports. A TProcessorFactory will be created that
   * always returns the specified processor.
   */
  public THsHaServer( TProcessor processor,
                      TNonblockingServerTransport serverTransport) {
    this(processor, serverTransport, new Options());
  }

  /**
   * Create server with given processor, server transport, and server options
   * using TBinaryProtocol for the protocol, and TFramedTransport.Factory on 
   * both input and output transports. A TProcessorFactory will be created that
   * always returns the specified processor.
   */
  public THsHaServer( TProcessor processor,
                      TNonblockingServerTransport serverTransport,
                      Options options) {
    this(new TProcessorFactory(processor), serverTransport, options);
  }

  /**
   * Create server with specified processor factory and server transport. Uses
   * default options. TBinaryProtocol is assumed. TFramedTransport.Factory is
   * used on both input and output transports.
   */
  public THsHaServer( TProcessorFactory processorFactory,
                      TNonblockingServerTransport serverTransport) {
    this(processorFactory, serverTransport, new Options());
  }
  
  /**
   * Create server with specified processor factory, server transport, and server
   * options. TBinaryProtocol is assumed. TFramedTransport.Factory is used on 
   * both input and output transports.
   */
  public THsHaServer( TProcessorFactory processorFactory,
                      TNonblockingServerTransport serverTransport,
                      Options options) {
    this(processorFactory, serverTransport, new TFramedTransport.Factory(), 
      new TBinaryProtocol.Factory(), options);
  }
  
  /**
   * Server with specified processor, server transport, and in/out protocol 
   * factory. Defaults will be used for in/out transport factory and server 
   * options.
   */
  public THsHaServer( TProcessor processor,
                      TNonblockingServerTransport serverTransport,
                      TProtocolFactory protocolFactory) {
    this(processor, serverTransport, protocolFactory, new Options());
  }

  /**
   * Server with specified processor, server transport, and in/out protocol 
   * factory. Defaults will be used for in/out transport factory and server 
   * options.
   */
  public THsHaServer( TProcessor processor,
                      TNonblockingServerTransport serverTransport,
                      TProtocolFactory protocolFactory,
                      Options options) {
    this(processor, serverTransport, new TFramedTransport.Factory(),
         protocolFactory);
  }
  
  /**
   * Create server with specified processor, server transport, in/out
   * transport factory, in/out protocol factory, and default server options. A
   * processor factory will be created that always returns the specified
   * processor.
   */
  public THsHaServer( TProcessor processor,
                      TNonblockingServerTransport serverTransport,
                      TTransportFactory transportFactory,
                      TProtocolFactory protocolFactory) {
    this(new TProcessorFactory(processor), serverTransport,
         transportFactory, protocolFactory);
  }
  
  /**
   * Create server with specified processor factory, server transport, in/out
   * transport factory, in/out protocol factory, and default server options.
   */
  public THsHaServer( TProcessorFactory processorFactory,
                      TNonblockingServerTransport serverTransport,
                      TTransportFactory transportFactory,
                      TProtocolFactory protocolFactory) {
    this(processorFactory, serverTransport,
         transportFactory, transportFactory,
         protocolFactory, protocolFactory, new Options());
  }
  
  /**
   * Create server with specified processor factory, server transport, in/out
   * transport factory, in/out protocol factory, and server options.
   */
  public THsHaServer( TProcessorFactory processorFactory,
                      TNonblockingServerTransport serverTransport,
                      TTransportFactory transportFactory,
                      TProtocolFactory protocolFactory,
                      Options options) {
    this(processorFactory, serverTransport,
         transportFactory, transportFactory,
         protocolFactory, protocolFactory,
         options);
  }
  
  /**
   * Create server with everything specified, except use default server options.
   */
  public THsHaServer( TProcessor processor,
                      TNonblockingServerTransport serverTransport,
                      TTransportFactory inputTransportFactory,
                      TTransportFactory outputTransportFactory,
                      TProtocolFactory inputProtocolFactory,
                      TProtocolFactory outputProtocolFactory) {
    this(new TProcessorFactory(processor), serverTransport,
         inputTransportFactory, outputTransportFactory,
         inputProtocolFactory, outputProtocolFactory);
  }
  
  /**
   * Create server with everything specified, except use default server options.
   */
  public THsHaServer( TProcessorFactory processorFactory,
                      TNonblockingServerTransport serverTransport, 
                      TTransportFactory inputTransportFactory,
                      TTransportFactory outputTransportFactory, 
                      TProtocolFactory inputProtocolFactory,
                      TProtocolFactory outputProtocolFactory) 
  {      
    this( processorFactory, serverTransport,
          inputTransportFactory, outputTransportFactory,
          inputProtocolFactory, outputProtocolFactory, new Options());
  }

  /** 
   * Create server with every option fully specified.
   */
  public THsHaServer( TProcessorFactory processorFactory,
                      TNonblockingServerTransport serverTransport, 
                      TTransportFactory inputTransportFactory,
                      TTransportFactory outputTransportFactory, 
                      TProtocolFactory inputProtocolFactory,
                      TProtocolFactory outputProtocolFactory, 
                      Options options) 
  {
    super(processorFactory, serverTransport,
          inputTransportFactory, outputTransportFactory,
          inputProtocolFactory, outputProtocolFactory);

    this.options = options;
  }

  /** @inheritdoc */
  @Override
  public void serve() {
    if (!startInvokerPool()) {
      return;
    }

    // start listening, or exit
    if (!startListening()) {
      return;
    }

    // start the selector, or exit
    if (!startSelectorThread()) {
      return;
    }

    // this will block while we serve
    joinSelector();

    gracefullyShutdownInvokerPool();
    
    // do a little cleanup
    stopListening();

    gracefullyShutdownInvokerPool();
  }

  protected boolean startInvokerPool() {
    // start the invoker pool
    LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    invoker = new ThreadPoolExecutor(options.minWorkerThreads, 
      options.maxWorkerThreads, options.stopTimeoutVal, options.stopTimeoutUnit, 
      queue);

    return true;
  }

  protected void gracefullyShutdownInvokerPool() {
    // try to gracefully shut down the executor service
    invoker.shutdown();

    // Loop until awaitTermination finally does return without a interrupted
    // exception. If we don't do this, then we'll shut down prematurely. We want
    // to let the executorService clear it's task queue, closing client sockets
    // appropriately.
    long timeoutMS = 10000; 
    long now = System.currentTimeMillis();
    while (timeoutMS >= 0) {
      try {
        invoker.awaitTermination(timeoutMS, TimeUnit.MILLISECONDS);
        break;
      } catch (InterruptedException ix) {
        long newnow = System.currentTimeMillis();
        timeoutMS -= (newnow - now);
        now = newnow;
      }
    }
  }

  /**
   * We override the standard invoke method here to queue the invocation for
   * invoker service instead of immediately invoking. The thread pool takes care of the rest.
   */
  @Override
  protected void invoke(TTransport inTrans, TTransport outTrans) {
    invoker.execute(new Invocation(inTrans, outTrans));
  }

  /** 
   * An Invocation represents a method call that is prepared to execute, given
   * an idle worker thread. It contains the input and output protocols the 
   * thread's processor should use to perform the usual Thrift invocation.
   */
  private class Invocation implements Runnable {
    
    private final TTransport input;
    private final TTransport output;
    
    public Invocation(final TTransport input, final TTransport output) {
      this.input = input;
      this.output = output;
    }
    
    /**
     * This method will set up the appropriate input and output protocols
     * and perform a single invocation. When the invocation is completed, it
     * will put itself onto the response queue.
     */
    public void run() {
      TProcessor processor = null;
      TProtocol inputProtocol = null;
      TProtocol outputProtocol = null;
      try {
        // set up processor
        processor = processorFactory_.getProcessor(input);
        
        // wrap our transports in the appropriate protocol
        inputProtocol = inputProtocolFactory_.getProtocol(input);
        outputProtocol = outputProtocolFactory_.getProtocol(output);

        // perform the actual invocation
        processor.process(inputProtocol, outputProtocol);
      } catch (TTransportException ttx) {
        // Assume the client died and continue silently
      } catch (TException tx) {
        // TODO: log better.
        tx.printStackTrace();
      } catch (Exception x) {
        // TODO: log better.
        x.printStackTrace();
      }
    }
  }
  
  public static class Options {
    public int minWorkerThreads = 5;
    public int maxWorkerThreads = Integer.MAX_VALUE;
    public int stopTimeoutVal = 60;
    public TimeUnit stopTimeoutUnit = TimeUnit.SECONDS;
  }
}