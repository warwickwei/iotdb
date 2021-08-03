/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.service.thrift;

import org.apache.iotdb.db.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.exception.runtime.RPCServiceException;
import org.apache.iotdb.db.utils.CommonUtils;
import org.apache.iotdb.rpc.RpcTransportFactory;
import org.apache.thrift.TBaseAsyncProcessor;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ThriftServiceThread extends Thread {

  private static final Logger logger = LoggerFactory.getLogger(ThriftServiceThread.class);
  private TServerTransport serverTransport;
  private TServer poolServer;
  private CountDownLatch threadStopLatch;

  private String serviceName;

  private TProtocolFactory protocolFactory;

  // currently, we can reuse the ProtocolFactory instance.
  private static TCompactProtocol.Factory compactProtocolFactory = new TCompactProtocol.Factory();
  private static TBinaryProtocol.Factory binaryProtocolFactory = new TBinaryProtocol.Factory();

  private void initProtocolFactory(boolean compress) {
    protocolFactory = getProtocolFactory(compress);
  }

  public static TProtocolFactory getProtocolFactory(boolean compress) {
    if (compress) {
      return compactProtocolFactory;
    } else {
      return binaryProtocolFactory;
    }
  }

  private void catchFailedInitialization(TTransportException e) throws RPCServiceException {
    close();
    if (threadStopLatch == null) {
      logger.debug("Stop Count Down latch is null");
    } else {
      logger.debug("Stop Count Down latch is {}", threadStopLatch.getCount());
    }
    if (threadStopLatch != null && threadStopLatch.getCount() == 1) {
      threadStopLatch.countDown();
    }
    logger.debug(
        "{}: close TThreadPoolServer and TServerSocket for {}",
        IoTDBConstant.GLOBAL_DB_NAME,
        serviceName);
    throw new RPCServiceException(
        String.format(
            "%s: failed to start %s, because ", IoTDBConstant.GLOBAL_DB_NAME, serviceName),
        e);
  }

  /** for asynced ThriftService */
  @SuppressWarnings("squid:S107")
  public ThriftServiceThread(
      TBaseAsyncProcessor processor,
      String serviceName,
      String threadsName,
      String bindAddress,
      int port,
      int maxWorkerThreads,
      int timeoutSecond,
      TServerEventHandler serverEventHandler,
      boolean compress,
      int connectionTimeoutInMS,
      int maxReadBufferBytes,
      ServerType serverType) {
    initProtocolFactory(compress);
    this.serviceName = serviceName;
    try {
      serverTransport = openNonblockingTransport(bindAddress, port, connectionTimeoutInMS);
      switch (serverType) {
        case SELECTOR:
          TThreadedSelectorServer.Args poolArgs =
              initAsyncedSelectorPoolArgs(
                  processor, threadsName, maxWorkerThreads, timeoutSecond, maxReadBufferBytes);
          poolServer = new TThreadedSelectorServer(poolArgs);
          break;
        case HSHA:
          THsHaServer.Args poolArgs1 =
              initAsyncedHshaPoolArgs(
                  processor, threadsName, maxWorkerThreads, timeoutSecond, maxReadBufferBytes);
          poolServer = new THsHaServer(poolArgs1);
          break;
      }
      poolServer.setServerEventHandler(serverEventHandler);
    } catch (TTransportException e) {
      catchFailedInitialization(e);
    }
  }

  /**
   * for synced ThriftServiceThread
   *
   * @param processor
   * @param serviceName
   * @param threadsName
   * @param bindAddress
   * @param port
   * @param maxWorkerThreads
   * @param timeoutSecond
   * @param serverEventHandler
   * @param compress
   */
  @SuppressWarnings("squid:S107")
  public ThriftServiceThread(
      TProcessor processor,
      String serviceName,
      String threadsName,
      String bindAddress,
      int port,
      int maxWorkerThreads,
      int timeoutSecond,
      TServerEventHandler serverEventHandler,
      boolean compress) {
    initProtocolFactory(compress);
    this.serviceName = serviceName;

    try {
      serverTransport = openTransport(bindAddress, port);
      TThreadPoolServer.Args poolArgs =
          initSyncedPoolArgs(processor, threadsName, maxWorkerThreads, timeoutSecond);
      poolServer = new TThreadPoolServer(poolArgs);
      poolServer.setServerEventHandler(serverEventHandler);
    } catch (TTransportException e) {
      catchFailedInitialization(e);
    }
  }

  private TThreadPoolServer.Args initSyncedPoolArgs(
      TProcessor processor, String threadsName, int maxWorkerThreads, int timeoutSecond) {
    TThreadPoolServer.Args poolArgs = new TThreadPoolServer.Args(serverTransport);
    poolArgs
        .maxWorkerThreads(maxWorkerThreads)
        .minWorkerThreads(CommonUtils.getCpuCores())
        .stopTimeoutVal(timeoutSecond);
    poolArgs.executorService =
        IoTDBThreadPoolFactory.createThriftRpcClientThreadPool(poolArgs, threadsName);
    poolArgs.processor(processor);
    poolArgs.protocolFactory(protocolFactory);
    poolArgs.transportFactory(RpcTransportFactory.INSTANCE);
    return poolArgs;
  }

  private TThreadedSelectorServer.Args initAsyncedSelectorPoolArgs(
      TBaseAsyncProcessor processor,
      String threadsName,
      int maxWorkerThreads,
      int timeoutSecond,
      int maxReadBufferBytes) {
    TThreadedSelectorServer.Args poolArgs =
        new TThreadedSelectorServer.Args((TNonblockingServerTransport) serverTransport);
    poolArgs.maxReadBufferBytes = maxReadBufferBytes;
    poolArgs.selectorThreads(CommonUtils.getCpuCores());
    poolArgs.executorService(
        IoTDBThreadPoolFactory.createThriftRpcClientThreadPool(
            CommonUtils.getCpuCores(),
            maxWorkerThreads,
            timeoutSecond,
            TimeUnit.SECONDS,
            threadsName));
    poolArgs.processor(processor);
    poolArgs.protocolFactory(protocolFactory);
    poolArgs.transportFactory(RpcTransportFactory.INSTANCE);
    return poolArgs;
  }

  private THsHaServer.Args initAsyncedHshaPoolArgs(
      TBaseAsyncProcessor processor,
      String threadsName,
      int maxWorkerThreads,
      int timeoutSecond,
      int maxReadBufferBytes) {
    THsHaServer.Args poolArgs = new THsHaServer.Args((TNonblockingServerTransport) serverTransport);
    poolArgs.maxReadBufferBytes = maxReadBufferBytes;
    poolArgs.executorService(
        IoTDBThreadPoolFactory.createThriftRpcClientThreadPool(
            CommonUtils.getCpuCores(),
            maxWorkerThreads,
            timeoutSecond,
            TimeUnit.SECONDS,
            threadsName));
    poolArgs.processor(processor);
    poolArgs.protocolFactory(protocolFactory);
    poolArgs.transportFactory(RpcTransportFactory.INSTANCE);
    return poolArgs;
  }

  @SuppressWarnings("java:S2259")
  private TServerTransport openTransport(String bindAddress, int port) throws TTransportException {
    int maxRetry = 5;
    long retryIntervalMS = 5000;
    TTransportException lastExp = null;
    for (int i = 0; i < maxRetry; i++) {
      try {
        return new TServerSocket(new InetSocketAddress(bindAddress, port));
      } catch (TTransportException e) {
        lastExp = e;
        try {
          Thread.sleep(retryIntervalMS);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    throw lastExp;
  }

  private TServerTransport openNonblockingTransport(
      String bindAddress, int port, int connectionTimeoutInMS) throws TTransportException {
    int maxRetry = 5;
    long retryIntervalMS = 5000;
    TTransportException lastExp = null;
    for (int i = 0; i < maxRetry; i++) {
      try {
        return new TNonblockingServerSocket(
            new InetSocketAddress(bindAddress, port), connectionTimeoutInMS);
      } catch (TTransportException e) {
        lastExp = e;
        try {
          Thread.sleep(retryIntervalMS);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    throw lastExp;
  }

  public void setThreadStopLatch(CountDownLatch threadStopLatch) {
    this.threadStopLatch = threadStopLatch;
  }

  @SuppressWarnings("squid:S2093") // socket will be used later
  @Override
  public void run() {
    logger.info("The {} service thread begin to run...", serviceName);
    try {
      poolServer.serve();
    } catch (Exception e) {
      throw new RPCServiceException(
          String.format("%s: %s exit, because ", IoTDBConstant.GLOBAL_DB_NAME, serviceName), e);
    } finally {
      close();
      if (threadStopLatch == null) {
        logger.debug("Stop Count Down latch is null");
      } else {
        logger.debug("Stop Count Down latch is {}", threadStopLatch.getCount());
      }

      if (threadStopLatch != null && threadStopLatch.getCount() == 1) {
        threadStopLatch.countDown();
      }
      logger.debug(
          "{}: close TThreadPoolServer and TServerSocket for {}",
          IoTDBConstant.GLOBAL_DB_NAME,
          serviceName);
    }
  }

  public synchronized void close() {
    if (poolServer != null) {
      poolServer.setShouldStop(true);
      poolServer.stop();

      poolServer = null;
    }
    if (serverTransport != null) {
      serverTransport.close();
      serverTransport = null;
    }
  }

  public boolean isServing() {
    if (poolServer != null) {
      return poolServer.isServing();
    }
    return false;
  }

  public static enum ServerType {
    SELECTOR,
    HSHA
  }
}
