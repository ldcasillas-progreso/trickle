/*
 * Copyright 2013-2014 Spotify AB. All rights reserved.
 *
 * The contents of this file are licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.trickle;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.*;
import static com.google.common.collect.ImmutableList.builder;
import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * A decorator class for Graph that holds bound values for input names thus making
 * graph runnable.
 *
 * This class is immutable and thread safe. Calls to any of the bind methods will
 * return a new instance containing that binding.
 *
 * @param <R>  The return type of the graph
 */
final class PreparedGraph<R> extends Graph<R> {

  private final GraphBuilder<R> graph;
  private final ImmutableMap<Input<?>, Object> inputBindings;
  private final boolean debug;

  private PreparedGraph(GraphBuilder<R> graph, ImmutableMap<Input<?>, Object> inputBindings,
                        boolean debug) {
    this.graph = checkNotNull(graph, "graph");
    this.inputBindings = checkNotNull(inputBindings, "inputBindings");
    this.debug = debug;
  }

  PreparedGraph(GraphBuilder<R> graph, boolean debug) {
    this(graph, ImmutableMap.<Input<?>, Object>of(), debug);
  }

  @Override
  public <P> Graph<R> bind(Input<P> input, P value) {
    return addToInputs(input, value);
  }

  @Override
  public <P> Graph<R> bind(Input<P> input, ListenableFuture<P> inputFuture) {
    return addToInputs(input, inputFuture);
  }

  @Override
  public Graph<R> debug(boolean debug) {
    return new PreparedGraph<R>(graph, inputBindings, debug);
  }

  @Override
  public ListenableFuture<R> run() {
    return run(directExecutor());
  }

  @Override
  public ListenableFuture<R> run(Executor executor) {
    return run(TraverseState.empty(executor, debug));
  }

  @Override
  ListenableFuture<R> run(TraverseState state) {
    state.addBindings(inputBindings);
    return future(state);
  }

  private ListenableFuture<R> future(final TraverseState state) {
    final ImmutableList.Builder<ListenableFuture<?>> futuresListBuilder = builder();

    // get node and value dependencies
    for (Dep<?> input : graph.getInputs()) {
      final ListenableFuture<?> inputFuture = input.getFuture(state);
      futuresListBuilder.add(inputFuture);
    }

    final ImmutableList<ListenableFuture<?>> futures = futuresListBuilder.build();

    final TraverseState.FutureCallInformation currentCall = state.record(this, futures);

    // future for signaling propagation - needs to include predecessors, too
    List<ListenableFuture<?>> mustHappenBefore = Lists.newArrayList(futures);
    for (Graph<?> predecessor : graph.getPredecessors()) {
      mustHappenBefore.add(state.futureForGraph(predecessor));
    }

    final ListenableFuture<List<Object>> allFuture = allAsList(mustHappenBefore);

    checkArgument(graph.getInputs().size() == futures.size(), "sanity check result: insane");

    return Futures.catchingAsync(
        nodeFuture(futures, allFuture, state.getExecutor()),
        Exception.class,
        new NodeExecutionFallback<R>(graph, currentCall, state)
    );
  }

  private ListenableFuture<R> nodeFuture(final ImmutableList<ListenableFuture<?>> values,
                                         final ListenableFuture<List<Object>> doneSignal,
                                         final Executor executor) {
    return Futures.transformAsync(
        doneSignal,
        new AsyncFunction<List<Object>, R>() {
          @Override
          public ListenableFuture<R> apply(List<Object> input) {
            // the input future is not going to be null unless there's a Trickle bug, so we should
            // be fine with an NPE in that case
            //noinspection NullableProblems
            return graph.getNode().run(
                Lists.transform(values, new Function<ListenableFuture<?>, Object>() {
                  @Override
                  public Object apply(ListenableFuture<?> input) {
                    return inputValueFromFuture(input);
                  }
                }));
          }
        },
        executor);
  }

  private Object inputValueFromFuture(ListenableFuture<?> input) {
    try {
      return Uninterruptibles.getUninterruptibly(input);
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), GraphExecutionException.class);
      throw Throwables.propagate(e);
    }
  }

  private PreparedGraph<R> addToInputs(Input<?> input, Object value) {
    checkState(!inputBindings.containsKey(input), "Duplicate binding for input: " + input);

    return new PreparedGraph<R>(
        graph,
        ImmutableMap.<Input<?>, Object>builder()
          .putAll(inputBindings)
          .put(input, value)
          .build(), debug);
  }

  @Override
  public String name() {
    return graph.name();
  }

  @Override
  public List<? extends NodeInfo> arguments() {
    return graph.arguments();
  }

  @Override
  public Iterable<? extends NodeInfo> predecessors() {
    return graph.predecessors();
  }

  @Override
  public Type type() {
    return graph.type();
  }

  @Override
  public String toString() {
    return graph.name();
  }

}
