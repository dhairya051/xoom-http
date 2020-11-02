// Copyright © 2012-2020 VLINGO LABS. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.http.resource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vlingo.actors.Actor;
import io.vlingo.actors.Logger;
import io.vlingo.actors.Returns;
import io.vlingo.actors.Stage;
import io.vlingo.actors.World;
import io.vlingo.common.BasicCompletes;
import io.vlingo.common.Completes;
import io.vlingo.common.Scheduled;
import io.vlingo.common.completes.SinkAndSourceBasedCompletes;
import io.vlingo.common.pool.ElasticResourcePool;
import io.vlingo.http.Context;
import io.vlingo.http.Filters;
import io.vlingo.http.Header;
import io.vlingo.http.Request;
import io.vlingo.http.RequestHeader;
import io.vlingo.http.RequestParser;
import io.vlingo.http.Response;
import io.vlingo.http.resource.Configuration.Sizing;
import io.vlingo.http.resource.Configuration.Timing;
import io.vlingo.http.resource.DispatcherPool.AbstractDispatcherPool;
import io.vlingo.http.resource.agent.AgentDispatcherPool;
import io.vlingo.http.resource.agent.HttpAgent;
import io.vlingo.http.resource.agent.HttpRequestChannelConsumer;
import io.vlingo.http.resource.agent.HttpRequestChannelConsumerProvider;
import io.vlingo.wire.channel.RequestChannelConsumer;
import io.vlingo.wire.channel.RequestResponseContext;
import io.vlingo.wire.fdx.bidirectional.ServerRequestResponseChannel;
import io.vlingo.wire.message.BasicConsumerByteBuffer;
import io.vlingo.wire.message.ConsumerByteBuffer;
import io.vlingo.wire.message.ConsumerByteBufferPool;

public class ServerActor extends Actor implements Server, HttpRequestChannelConsumerProvider, Scheduled<Object> {
  static final String ChannelName = "server-request-response-channel";
  static final String ServerName = "vlingo-http-server";

  private final HttpAgent agent;
  private final ServerRequestResponseChannel channel;
  private final DispatcherPool dispatcherPool;
  private final Filters filters;
  private final int maxMessageSize;
  private final Map<String,RequestResponseHttpContext> requestsMissingContent;
  private final long requestMissingContentTimeout;
  private final ConsumerByteBufferPool responseBufferPool;
  private final World world;


  public ServerActor(
          final Resources resources,
          final Filters filters,
          final int port,
          final int dispatcherPoolSize)
  throws Exception {
    final long start = Instant.now().toEpochMilli();

    this.agent = HttpAgent.initialize(this, port, false, dispatcherPoolSize, logger());

    this.channel = null;                            // unused
    this.filters = filters;
    this.world = stage().world();
    this.dispatcherPool = new AgentDispatcherPool(stage(), resources, dispatcherPoolSize);
    this.requestsMissingContent = new HashMap<>();  // unused
    this.maxMessageSize = 0;                        // unused
    this.responseBufferPool = null;                 // unused
    this.requestMissingContentTimeout = -1;         // unused

    final long end = Instant.now().toEpochMilli();

    logger().info("Server " + ServerName + " is listening on port: " + port + " started in " + (end - start) + " ms");

    logResourceMappings(resources);
  }

  public ServerActor(
          final Resources resources,
          final Filters filters,
          final int port,
          final Sizing sizing,
          final Timing timing,
          final String channelMailboxTypeName)
  throws Exception {
    final long start = Instant.now().toEpochMilli();

    this.agent = null;                              // unused
    this.filters = filters;
    this.world = stage().world();
    this.requestsMissingContent = new HashMap<>();
    this.maxMessageSize = sizing.maxMessageSize;

    try {
      responseBufferPool = new ConsumerByteBufferPool(
        ElasticResourcePool.Config.of(sizing.maxBufferPoolSize), sizing.maxMessageSize);

      this.dispatcherPool = new ServerDispatcherPool(stage(), resources, sizing.dispatcherPoolSize);

      this.channel =
              ServerRequestResponseChannel.start(
                      stage(),
                      stage().world().addressFactory().withHighId(ChannelName),
                      channelMailboxTypeName,
                      this,
                      port,
                      ChannelName,
                      sizing.processorPoolSize,
                      sizing.maxBufferPoolSize,
                      sizing.maxMessageSize,
                      timing.probeInterval,
                      timing.probeTimeout);

      final long end = Instant.now().toEpochMilli();

      logger().info("Server " + ServerName + " is listening on port: " + port + " started in " + (end - start) + " ms");

      this.requestMissingContentTimeout = timing.requestMissingContentTimeout;

      logResourceMappings(resources);

    } catch (Exception e) {
      final String message = "Failed to start server because: " + e.getMessage();
      logger().error(message, e);
      throw new IllegalStateException(message);
    }
  }

  //=========================================
  // Server
  //=========================================

  @Override
  public Completes<Boolean> shutDown() {
    stop();

    return completes().with(true);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Completes<Boolean> startUp() {
    if (requestMissingContentTimeout > 0) {
      stage().scheduler().schedule(selfAs(Scheduled.class), null, 1000L, requestMissingContentTimeout);
    }

    return completes().with(true);
  }


  //=========================================
  // RequestChannelConsumerProvider
  //=========================================

  @Override
  public RequestChannelConsumer requestChannelConsumer() {
    return httpRequestChannelConsumer();
  }

  @Override
  public HttpRequestChannelConsumer httpRequestChannelConsumer() {
    return new ServerRequestChannelConsumer(dispatcherPool.dispatcher());
  }


  //=========================================
  // Scheduled
  //=========================================

  @Override
  public void intervalSignal(final Scheduled<Object> scheduled, final Object data) {
    failTimedOutMissingContentRequests();
  }


  //=========================================
  // Stoppable
  //=========================================

  @Override
  public void stop() {
    logger().info("Server stopping...");

    if (agent != null) {
      agent.close();
    } else {
      failTimedOutMissingContentRequests();

      channel.stop();
      channel.close();

      dispatcherPool.close();

      filters.stop();
    }

    logger().info("Server stopped.");

    super.stop();
  }


  //=========================================
  // internal implementation
  //=========================================

  private void failTimedOutMissingContentRequests() {
    if (isStopped()) return;
    if (requestsMissingContent.isEmpty()) return;

    final List<String> toRemove = new ArrayList<>(); // prevent ConcurrentModificationException

    for (final String id : requestsMissingContent.keySet()) {
      final RequestResponseHttpContext requestResponseHttpContext = requestsMissingContent.get(id);

      if (requestResponseHttpContext.requestResponseContext.hasConsumerData()) {
        final RequestParser parser = requestResponseHttpContext.requestResponseContext.consumerData();
        if (parser.hasMissingContentTimeExpired(requestMissingContentTimeout)) {
          requestResponseHttpContext.requestResponseContext.consumerData(null);
          toRemove.add(id);
          requestResponseHttpContext.httpContext.completes.with(Response.of(Response.Status.BadRequest, "Missing content with timeout."));
          requestResponseHttpContext.requestResponseContext.consumerData(null);
        }
      } else {
        toRemove.add(id); // already closed?
      }
    }

    for (final String id : toRemove) {
      requestsMissingContent.remove(id);
    }
  }

  private void logResourceMappings(final Resources resources) {
    final Logger logger = logger();
    for (final String resourceName : resources.namedResources.keySet()) {
      resources.namedResources.get(resourceName).log(logger);
    }
  }


  private static class ServerDispatcherPool extends AbstractDispatcherPool {
    private int dispatcherPoolIndex;

    ServerDispatcherPool(final Stage stage, final Resources resources, final int dispatcherPoolSize) {
      super(stage, resources, dispatcherPoolSize);

      this.dispatcherPoolIndex = 0;
    }

    @Override
    public Dispatcher dispatcher() {
      if (dispatcherPoolIndex >= dispatcherPool.length) {
        dispatcherPoolIndex = 0;
      }
      return dispatcherPool[dispatcherPoolIndex++];
    }
  }

  //=========================================
  // RequestChannelConsumer
  //=========================================

//  private static final AtomicLong nextInstanceId = new AtomicLong(0);
//
//  private int fullCount = 0;
//  private int missingCount = 0;
//  private final long instanceId = nextInstanceId.incrementAndGet();

  private class ServerRequestChannelConsumer implements HttpRequestChannelConsumer {
    private final Dispatcher dispatcher;

    ServerRequestChannelConsumer(final Dispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Override
    public void closeWith(final RequestResponseContext<?> requestResponseContext, final Object data) {
//    logger().debug("===================== CLOSE WITH: " + data);
      if (data != null) {
        final Request request = filters.process((Request) data);
        final Completes<Response> completes = responseCompletes.of(requestResponseContext, /*request,*/ false, request.headers.headerOf(RequestHeader.XCorrelationID), true);
        final Context context = new Context(requestResponseContext, request, world.completesFor(Returns.value(completes)));
        dispatcher.dispatchFor(context);
      }
    }

    @Override
    public void consume(final RequestResponseContext<?> requestResponseContext, final ConsumerByteBuffer buffer) {
      boolean wasIncompleteContent = false;
      boolean missingContent = false;

      try {
        final RequestParser parser;

        if (!requestResponseContext.hasConsumerData()) {
          parser = RequestParser.parserFor(buffer.asByteBuffer());
          requestResponseContext.consumerData(parser);
        } else {
          parser = requestResponseContext.consumerData();
          wasIncompleteContent = parser.isMissingContent();
//        logger().debug("============== (" + instanceId + ") WAS MISSING CONTENT FOR (" + (missingCount) + "): " + wasIncompleteContent);
//        if (wasIncompleteContent) {
//          logger().debug(
//                  parser.currentRequestText() +
//                  "\nNOW CONSUMING:\n" +
//                  Converters.bytesToText(buffer.array(), 0, buffer.limit()));
//        }
          parser.parseNext(buffer.asByteBuffer());
        }

        Context context = null;

        while (parser.hasFullRequest()) {
          context = consume(requestResponseContext, parser.fullRequest(), wasIncompleteContent);
        }

        if (parser.isMissingContent() && !requestsMissingContent.containsKey(requestResponseContext.id())) {
//        logger().debug("==============(" + instanceId + ") MISSING REQUEST CONTENT FOR (" + (++missingCount) + "): \n" + parser.currentRequestText());
          missingContent = true;
          if (context == null) {
            final Completes<Response> completes = responseCompletes.of(requestResponseContext.typed(), /*unfilteredRequest,*/ true, null, true);
            context = new Context(world.completesFor(Returns.value(completes)));
          }
          requestsMissingContent.put(requestResponseContext.id(), new RequestResponseHttpContext(requestResponseContext, context));
        }

      } catch (Exception e) {
//      logger().debug("=====================(" + instanceId + ") BAD REQUEST (1): " + unfilteredRequest);
//      final String requestContentText = Converters.bytesToText(buffer.array(), 0, buffer.limit());
//      logger().debug("=====================(" + instanceId + ") BAD REQUEST (2): " + requestContentText);
        logger().error("Request parsing failed.", e);
        responseCompletes.of(requestResponseContext, /*unfilteredRequest,*/ missingContent, null, false).with(Response.of(Response.Status.BadRequest, e.getMessage()));
      } finally {
        buffer.release();
      }
    }

    @Override
    public void consume(final RequestResponseContext<?> requestResponseContext, final Request request) {
      consume(requestResponseContext, request, false);
    }

    private Context consume(
            final RequestResponseContext<?> requestResponseContext,
            final Request request,
            final boolean wasIncompleteContent) {

//    logger().debug("==============(" + instanceId + ") FULL REQUEST (" + (++fullCount) + "): \n" + unfilteredRequest);

      final boolean keepAlive = determineKeepAlive(requestResponseContext, request);
      final Request filteredRequest = filters.process(request);
      final Completes<Response> completes = responseCompletes.of(requestResponseContext, /*unfilteredRequest,*/ false, filteredRequest.headers.headerOf(RequestHeader.XCorrelationID), keepAlive);
      final Context context = new Context(requestResponseContext, filteredRequest, world.completesFor(Returns.value(completes)));
      dispatcher.dispatchFor(context);

      if (wasIncompleteContent) {
        requestsMissingContent.remove(requestResponseContext.id());
      }

      return context;
    }

    private boolean determineKeepAlive(final RequestResponseContext<?> requestResponseContext, final Request unfilteredRequest) {
      final boolean keepAlive =
              unfilteredRequest
                .headerValueOr(RequestHeader.Connection, Header.ValueKeepAlive)
                .equalsIgnoreCase(Header.ValueKeepAlive);

//    if (keepAlive) {
//      logger().debug("///////// SERVER REQUEST KEEP ALIVE /////////(" + instanceId + ")");
//    } else {
//      logger().debug("///////// SERVER REQUEST EAGER CLOSE /////////(" + instanceId + ")");
//    }

      return keepAlive;
    }
  }

  //=========================================
  // RequestResponseHttpContext
  //=========================================

  private class RequestResponseHttpContext {
    final Context httpContext;
    final RequestResponseContext<?> requestResponseContext;

    RequestResponseHttpContext(final RequestResponseContext<?> requestResponseContext, final Context httpContext) {
      this.requestResponseContext = requestResponseContext;
      this.httpContext = httpContext;
    }
  }

  //=========================================
  // ResponseCompletes
  //=========================================

  ResponseCompletes responseCompletes = new ResponseCompletes();
  private class ResponseCompletes {
    public Completes<Response> of(final RequestResponseContext<?> requestResponseContext, /*final Request request,*/ final boolean missingContent, final Header correlationId, final boolean keepAlive) {
      if (SinkAndSourceBasedCompletes.isToggleActive()) {
        return new SinkBasedBasedResponseCompletes(requestResponseContext, /*request,*/ missingContent, correlationId, keepAlive);
      } else {
        return new BasicCompletedBasedResponseCompletes(requestResponseContext, /*request,*/ missingContent, correlationId, keepAlive);
      }
    }
  }

  private class BasicCompletedBasedResponseCompletes extends BasicCompletes<Response> {
    final Header correlationId;
    final boolean keepAlive;
    final boolean missingContent;
//  final Request request;
    final RequestResponseContext<?> requestResponseContext;

    BasicCompletedBasedResponseCompletes(final RequestResponseContext<?> requestResponseContext, /*final Request request,*/ final boolean missingContent, final Header correlationId, final boolean keepAlive) {
      super(stage().scheduler());
      this.requestResponseContext = requestResponseContext;
//    this.request = request;
      this.missingContent = missingContent;
      this.correlationId = correlationId;
      this.keepAlive = keepAlive;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <O> Completes<O> with(final O response) {
      final Response unfilteredResponse = (Response) response;
      final Response filtered = filters.process(unfilteredResponse);
      final Response completedResponse = filtered.include(correlationId);
      final boolean closeAfterResponse = closeAfterResponse(unfilteredResponse);
      if (agent == null) {
        final ConsumerByteBuffer buffer = bufferFor(completedResponse);
        requestResponseContext.respondWith(completedResponse.into(buffer), closeAfterResponse);
      } else {
//      System.out.println("============> SERVER RESPONSE: \n" + completedResponse);
        requestResponseContext.respondWith(completedResponse, closeAfterResponse);
      }
      return (Completes<O>) this;
    }

    private ConsumerByteBuffer bufferFor(final Response response) {
      final int size = response.size();
      try {
        if (size < maxMessageSize) {
          return responseBufferPool.acquire("ServerActor#BasicCompletedBasedResponseCompletes#bufferFor");
        }
        final int difference = size - maxMessageSize;
        throw new IllegalStateException("Response exceeds maxMessageSize(" + maxMessageSize + ") by " + difference + ".");
        
      } catch (IllegalStateException e) {
        final String message = e.getMessage() + "\nAllocating size " + size + " instead.";
        logger().error(message, e);
        return BasicConsumerByteBuffer.allocate(0, size);
      }
    }

    private boolean closeAfterResponse(final Response response) {
      if (missingContent) return false;

      final char statusCategory = response.statusCode.charAt(0);
      if (statusCategory == '4' || statusCategory == '5') {
//      logger().debug(
//              "///////// SERVER RESPONSE CLOSED FOLLOWING ///////// KEEP-ALIVE: " + keepAlive +
//              "\n///////// REQUEST\n" + request +
//              "\n///////// RESPONSE\n" + response);
        return keepAlive;
      }

      final boolean keepAliveAfterResponse =
              keepAlive ||
              response
                .headerValueOr(RequestHeader.Connection, Header.ValueKeepAlive)
                .equalsIgnoreCase(Header.ValueKeepAlive);

//   if (keepAliveAfterResponse) {
//      logger().debug("///////// SERVER RESPONSE KEEP ALIVE ////////");
//    } else {
//      logger().debug("///////// SERVER RESPONSE CLOSED FOLLOWING /////////\n" + response);
//    }

      return !keepAliveAfterResponse;
    }
  }

  private class SinkBasedBasedResponseCompletes extends SinkAndSourceBasedCompletes<Response> {
    final Header correlationId;
    final boolean keepAlive;
    final boolean missingContent;
//    final Request request;
    final RequestResponseContext<?> requestResponseContext;

    SinkBasedBasedResponseCompletes(final RequestResponseContext<?> requestResponseContext, /* final Request request,*/ final boolean missingContent, final Header correlationId, final boolean keepAlive) {
      super(stage().scheduler());
      this.requestResponseContext = requestResponseContext;
//    this.request = request;
      this.missingContent = missingContent;
      this.correlationId = correlationId;
      this.keepAlive = keepAlive;
    }

    @Override
    public <O> Completes<O> with(final O response) {
      final Response unfilteredResponse = (Response) response;
      final Response filtered = filters.process(unfilteredResponse);
      final Response completedResponse = filtered.include(correlationId);
      final boolean closeAfterResponse = closeAfterResponse(unfilteredResponse);
      if (agent == null) {
        final ConsumerByteBuffer buffer = bufferFor(completedResponse);
        requestResponseContext.respondWith(completedResponse.into(buffer), closeAfterResponse);
      } else {
        requestResponseContext.respondWith(completedResponse, closeAfterResponse);
      }
      return super.with(response);
    }

    private ConsumerByteBuffer bufferFor(final Response response) {
      final int size = response.size();
      if (size < maxMessageSize) {
        return responseBufferPool.acquire("ServerActor#SinkBasedBasedResponseCompletes#bufferFor");
      }

      return BasicConsumerByteBuffer.allocate(0, size + 1024);
    }

    private boolean closeAfterResponse(final Response response) {
      if (missingContent) return false;

      final char statusCategory = response.statusCode.charAt(0);
      if (statusCategory == '4' || statusCategory == '5') {
//        logger().debug(
//                "///////// SERVER RESPONSE CLOSED FOLLOWING ///////// KEEP-ALIVE: " + keepAlive +
//                "\n///////// REQUEST\n" + request +
//                "\n///////// RESPONSE\n" + response);
        return keepAlive;
      }

      final boolean keepAliveAfterResponse =
              keepAlive ||
              response
                .headerValueOr(RequestHeader.Connection, Header.ValueKeepAlive)
                .equalsIgnoreCase(Header.ValueKeepAlive);

//      if (keepAliveAfterResponse) {
//        logger().debug("///////// SERVER RESPONSE KEEP ALIVE ////////");
//      } else {
//        logger().debug("///////// SERVER RESPONSE CLOSED FOLLOWING /////////\n" + response);
//      }

      return !keepAliveAfterResponse;
    }
  }
}
