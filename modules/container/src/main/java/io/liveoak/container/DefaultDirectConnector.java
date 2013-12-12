/*
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at http://www.eclipse.org/legal/epl-v10.html
 */
package io.liveoak.container;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.liveoak.container.codec.driver.RootEncodingDriver;
import io.liveoak.container.codec.state.ResourceStateEncoder;
import io.liveoak.container.protocols.PipelineConfigurator;
import io.liveoak.container.subscriptions.SubscriptionWatcher;
import io.liveoak.spi.Container;
import io.liveoak.spi.CreateNotSupportedException;
import io.liveoak.spi.DeleteNotSupportedException;
import io.liveoak.spi.NotAcceptableException;
import io.liveoak.spi.NotAuthorizedException;
import io.liveoak.spi.ReadNotSupportedException;
import io.liveoak.spi.RequestContext;
import io.liveoak.spi.RequestType;
import io.liveoak.spi.ResourceAlreadyExistsException;
import io.liveoak.spi.ResourceErrorResponse;
import io.liveoak.spi.ResourceException;
import io.liveoak.spi.ResourceNotFoundException;
import io.liveoak.spi.ResourcePath;
import io.liveoak.spi.ResourceProcessingException;
import io.liveoak.spi.ResourceRequest;
import io.liveoak.spi.UpdateNotSupportedException;
import io.liveoak.spi.container.DirectConnector;
import io.liveoak.spi.ResourceResponse;
import io.liveoak.spi.container.SubscriptionManager;
import io.liveoak.spi.resource.async.Resource;
import io.liveoak.spi.state.ResourceState;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Connector to the resoure-container that works in-VM, avoiding network traffic.
 *
 * <p>Inputs and outputs through the connector avoid all serialization to bytes
 * and work only in terms of {@link io.liveoak.spi.state.ResourceState} instances.</p>
 *
 * @author Bob McWhirter
 */
public class DefaultDirectConnector implements DirectConnector {

    private class DirectCallbackHandler extends ChannelOutboundHandlerAdapter {
        public DirectCallbackHandler() {
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            promise.addListener((f) -> {
                Object o = DefaultDirectConnector.this.channel.readOutbound();
                DefaultDirectConnector.this.dispatch(o);
            });
            super.write(ctx, msg, promise);    //To change body of overridden methods use File | Settings | File Templates.
        }
    }


    /**
     * Constructor a new connector for a container.
     *
     */
    public DefaultDirectConnector(PipelineConfigurator pipelineConfigurator) {
        this.channel = new EmbeddedChannel( new DirectCallbackHandler() );
        this.channel.pipeline().removeLast();
        pipelineConfigurator.setupDirectConnector(this.channel.pipeline());
        this.channel.pipeline().addLast( new LastInboundHandler() );
        this.channel.readInbound();
    }

    /**
     * Fetch an actual Resource component given its path.
     *
     * @param path The path of the resource.
     * @return The actual resource.
     * @throws io.liveoak.spi.ResourceException    If the resource cannot be found.
     * @throws java.util.concurrent.ExecutionException   If an error occurs while asynchronously fetching.
     * @throws InterruptedException If interrupted before completing.
     */
    public Resource fetch(String path) throws ResourceException, ExecutionException, InterruptedException {
        CompletableFuture<Resource> future = new CompletableFuture<>();
        RequestContext context = new RequestContext.Builder().build();

        read(context, path, (response) -> {
            if (response.responseType() == ResourceResponse.ResponseType.READ) {
                try {
                    future.complete(response.resource());
                } catch (Exception e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            } else if (response instanceof ResourceErrorResponse) {
                handleError((DefaultResourceErrorResponse) response, future);
            } else {
                future.complete(null);
            }
        });

        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResourceException) {
                throw (ResourceException) e.getCause();
            }
            throw e;
        }
    }

    /**
     * Perform an asynchronous CREATE action.
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @param state   The inbound state to use during creation.
     * @param handler Asynchronously result handler.
     */
    public void create(RequestContext context, String path, ResourceState state, Consumer<ResourceResponse> handler) {
        DefaultResourceRequest request = new DefaultResourceRequest.Builder(RequestType.CREATE, new ResourcePath(path))
                .requestContext(context)
                .resourceState(state)
                .build();
        this.handlers.put(request, handler);
        this.channel.writeInbound(request);
    }

    /**
     * Perform a synchronous CREATE action.
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @param state   The inbound state to use during creation.
     * @return The resulting state of the request.
     * @throws io.liveoak.spi.ResourceException
     * @throws java.util.concurrent.ExecutionException
     * @throws InterruptedException
     */
    public ResourceState create(RequestContext context, String path, ResourceState state) throws ResourceException, ExecutionException, InterruptedException {
        CompletableFuture<ResourceState> future = new CompletableFuture<>();

        create(context, path, state, (response) -> {
            if (response.responseType() == ResourceResponse.ResponseType.CREATED) {
                try {
                    future.complete(encode(context, response.resource()));
                } catch (Exception e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            } else if (response instanceof ResourceErrorResponse) {
                handleError((DefaultResourceErrorResponse) response, future);
            } else {
                future.complete(null);
            }
        });

        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResourceException) {
                throw (ResourceException) e.getCause();
            }
            throw e;
        }
    }

    /**
     * Perform an asynchronous READ action.
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @param handler Asynchronously result handler.
     */
    public void read(RequestContext context, String path, Consumer<ResourceResponse> handler) {
        DefaultResourceRequest request = new DefaultResourceRequest.Builder(RequestType.READ, new ResourcePath(path))
                .requestContext(context)
                .build();
        this.handlers.put(request, handler);
        this.channel.writeInbound(request);
    }

    /**
     * Perform a synchronous READ action.
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @return The resulting state of the request.
     * @throws io.liveoak.spi.ResourceException
     * @throws java.util.concurrent.ExecutionException
     * @throws InterruptedException
     */
    public ResourceState read(RequestContext context, String path) throws ResourceException, ExecutionException, InterruptedException {
        CompletableFuture<ResourceState> future = new CompletableFuture<>();

        read(context, path, (response) -> {
            if (response.responseType() == DefaultResourceResponse.ResponseType.READ) {
                try {
                    future.complete(encode(context, response.resource()));
                } catch (Exception e) {
                    DefaultResourceErrorResponse resourceErrorResponse = null;
                    if (e instanceof ResourceProcessingException) {
                        ResourceProcessingException rpe = (ResourceProcessingException)e;
                        resourceErrorResponse = new DefaultResourceErrorResponse(response.inReplyTo(), ResourceErrorResponse.ErrorType.NOT_ACCEPTABLE, rpe.getMessage(), rpe.getCause());
                    } else {
                        e.printStackTrace(); //TODO: figure out how to pass this to the client
                        resourceErrorResponse = new DefaultResourceErrorResponse( response.inReplyTo(), ResourceErrorResponse.ErrorType.INTERNAL_ERROR);
                    }
                    handleError(resourceErrorResponse, future);
                }
            } else if (response instanceof ResourceErrorResponse) {
                handleError((DefaultResourceErrorResponse) response, future);
            } else {
                future.complete(null);
            }
        });

        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResourceException) {
                throw (ResourceException) e.getCause();
            }
            throw e;
        }
    }

    /**
     * Perform an asynchronous UPDATE action.
     *
     * <p>UPDATE has UPSERT semantics, in that if an attempt to
     * update a non-existant resource fails, an attempt is made
     * to create a resource at that location in the implied parent
     * container resource.</p>
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @param state   The inbound state to use during update.
     * @param handler Asynchronously result handler.
     */
    public void update(RequestContext context, String path, ResourceState state, Consumer<ResourceResponse> handler) {
        DefaultResourceRequest request = new DefaultResourceRequest.Builder(RequestType.UPDATE, new ResourcePath(path))
                .requestContext(context)
                .resourceState(state)
                .build();
        this.handlers.put(request, handler);
        this.channel.writeInbound(request);
    }

    /**
     * Perform a synchronous UPDATE action.
     *
     * <p>UPDATE has UPSERT semantics, in that if an attempt to
     * update a non-existant resource fails, an attempt is made
     * to create a resource at that location in the implied parent
     * container resource.</p>
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @param state   The inbound state to use during creation.
     * @return The resulting state of the request.
     * @throws io.liveoak.spi.ResourceException
     * @throws java.util.concurrent.ExecutionException
     * @throws InterruptedException
     */
    public ResourceState update(RequestContext context, String path, ResourceState state) throws ResourceException, ExecutionException, InterruptedException {
        CompletableFuture<ResourceState> future = new CompletableFuture<>();

        update(context, path, state, (response) -> {
            if (response.responseType() == DefaultResourceResponse.ResponseType.UPDATED) {
                try {
                    future.complete(encode(context, response.resource()));
                } catch (Exception e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            } else if (response instanceof ResourceErrorResponse) {
                handleError((DefaultResourceErrorResponse) response, future);
            } else {
                future.complete(null);
            }
        });

        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResourceException) {
                throw (ResourceException) e.getCause();
            }
            throw e;
        }
    }

    /**
     * Perform an asynchronous DELETE action.
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @param handler Asynchronously result handler.
     */
    public void delete(RequestContext context, String path, Consumer<ResourceResponse> handler) {
        DefaultResourceRequest request = new DefaultResourceRequest.Builder(RequestType.DELETE, new ResourcePath(path))
                .requestContext(context)
                .build();
        this.handlers.put(request, handler);
        this.channel.writeInbound(request);
    }

    /**
     * Perform a synchronous DELTE action.
     *
     * @param context The request context.
     * @param path    The path portion of the resource's URI.
     * @return The resulting state of the request.
     * @throws io.liveoak.spi.ResourceException
     * @throws java.util.concurrent.ExecutionException
     * @throws InterruptedException
     */
    public ResourceState delete(RequestContext context, String path) throws ResourceException, ExecutionException, InterruptedException {
        CompletableFuture<ResourceState> future = new CompletableFuture<>();

        delete(context, path, (response) -> {
            if (response.responseType() == ResourceResponse.ResponseType.DELETED) {
                try {
                    future.complete(encode(context, response.resource()));
                } catch (Exception e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            } else if (response instanceof ResourceErrorResponse) {
                handleError((DefaultResourceErrorResponse) response, future);
            } else {
                future.complete(null);
            }
        });

        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResourceException) {
                throw (ResourceException) e.getCause();
            }
            throw e;
        }
    }


    /**
     * Encode (for some cheap value of 'encode') a resulting resource into a ResourceState.
     *
     * @param context  The request context (for expansion/fields).
     * @param resource The resource to encode.
     * @return The encoded resource state.
     * @throws Exception
     */
    protected ResourceState encode(RequestContext context, Resource resource) throws Exception {
        CompletableFuture<ResourceState> state = new CompletableFuture<>();

        ResourceStateEncoder encoder = new ResourceStateEncoder();
        RootEncodingDriver driver = new RootEncodingDriver(context, encoder, resource, () -> {
            state.complete(encoder.root());
        });

        driver.encode();

        return state.get();
    }

    /**
     * Convert an error response into an exception.
     *
     * @param response The response.
     * @param future   The future for populating.
     */
    void handleError(DefaultResourceErrorResponse response, CompletableFuture<?> future) {
        switch (response.errorType()) {
            case NOT_AUTHORIZED:
                future.completeExceptionally(new NotAuthorizedException(response.inReplyTo().resourcePath().toString()));
                break;
            case NOT_ACCEPTABLE:
                future.completeExceptionally(new NotAcceptableException(response.inReplyTo().resourcePath().toString(), response.message(), response.cause()));
                break;
            case NO_SUCH_RESOURCE:
                future.completeExceptionally(new ResourceNotFoundException(response.inReplyTo().resourcePath().toString()));
                break;
            case RESOURCE_ALREADY_EXISTS:
                future.completeExceptionally(new ResourceAlreadyExistsException(response.inReplyTo().state().id()));
                break;
            case CREATE_NOT_SUPPORTED:
                future.completeExceptionally(new CreateNotSupportedException(response.inReplyTo().resourcePath().toString()));
                break;
            case READ_NOT_SUPPORTED:
                future.completeExceptionally(new ReadNotSupportedException(response.inReplyTo().resourcePath().toString()));
                break;
            case UPDATE_NOT_SUPPORTED:
                future.completeExceptionally(new UpdateNotSupportedException(response.inReplyTo().resourcePath().toString()));
                break;
            case DELETE_NOT_SUPPORTED:
                future.completeExceptionally(new DeleteNotSupportedException(response.inReplyTo().resourcePath().toString()));
                break;
            case INTERNAL_ERROR:
                future.completeExceptionally( response.cause() );
                break;
        }
    }

    /**
     * Dispatch a downstream/outbound response to the appropriate asynchronous handler.
     *
     * @param obj The object to dispatch.
     */
    void dispatch(Object obj) {
        if (obj instanceof DefaultResourceResponse) {
            Consumer<ResourceResponse> handler = this.handlers.remove(((DefaultResourceResponse) obj).inReplyTo());
            if (handler != null) {
                handler.accept((DefaultResourceResponse) obj);
            }
        }
    }

    private EmbeddedChannel channel;
    private Map<ResourceRequest, Consumer<ResourceResponse>> handlers = new HashMap<>();

    private final class LastInboundHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            channel.lastInboundBuffer().add(msg);
        }
    }

}