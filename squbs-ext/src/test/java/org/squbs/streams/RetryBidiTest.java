/*
 * Copyright 2017 PayPal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.squbs.streams;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.BidiFlow;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.javadsl.TestSink;
import org.testng.annotations.Test;
import scala.concurrent.duration.Duration;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class RetryBidiTest {

    final private ActorSystem system = ActorSystem.create("RetryBidiTest");
    final private Materializer mat = ActorMaterializer.create(system);
    final private Try<String> failure = Failure.apply(new Exception("failed"));

    @Test
    public void testRetryBidi() throws ExecutionException, InterruptedException {

        final Flow<Pair<String, UUID>, Pair<Try<String>, UUID>, NotUsed> flow =
                Flow.<Pair<String, UUID>>create()
                        .map(elem -> new Pair<Try<String>, UUID>(Success.apply(elem.first()), elem.second()));

        final BidiFlow<Pair<String, UUID>, Pair<String, UUID>, Pair<Try<String>, UUID>, Pair<Try<String>, UUID>,
                NotUsed> retry = RetryBidi.create(2L);

        final CompletionStage<List<Try<String>>> result =
                Source.from(Arrays.asList("a", "b", "c"))
                        .map(s -> new Pair<>(s, UUID.randomUUID()))
                        .via(retry.join(flow))
                        .map(Pair::first)
                        .runWith(Sink.seq(), mat);

        final List<Try<String>> expected = Arrays.asList(Success.apply("a"), Success.apply("b"), Success.apply("c"));
        List<Try<String>> actual = result.toCompletableFuture().get();
        assertEquals(actual.size(), 3);
        assertTrue(actual.containsAll(expected), "Did not get the expected elements from retry stage");
    }

    @Test
    public void testRetryBidiWithFailures() throws ExecutionException, InterruptedException {

        final Flow<Pair<String, UUID>, Pair<Try<String>, UUID>, NotUsed> bottom =
                Flow.<Pair<String, UUID>>create()
                        .map(elem -> {
                            if (elem.first().equals("a") || elem.first().equals("c"))
                                return new Pair<>(failure, elem.second());
                            else
                                return new Pair<>(Success.apply(elem.first()), elem.second());
                            });
        final BidiFlow<Pair<String, UUID>, Pair<String, UUID>, Pair<Try<String>, UUID>,
                Pair<Try<String>, UUID>, NotUsed> retry = RetryBidi.create(2L);

        final CompletionStage<List<Try<String>>> result =
                Source.from(Arrays.asList("a", "b", "c"))
                        .map(s -> new Pair<>(s, UUID.randomUUID()))
                        .via(retry.join(bottom))
                        .map(Pair::first)
                        .runWith(Sink.seq(), mat);

        final List<Try<String>> expected = Arrays.asList(failure, Success.apply("b"), failure);
        List<Try<String>> actual = result.toCompletableFuture().get();
        assertEquals(actual.size(), 3);
        assertTrue(actual.containsAll(expected), "Did not get the expected elements from retry stage");
    }

    @Test
    public void testRetryBidiWithRetryFlow() throws ExecutionException, InterruptedException {

        final Flow<Pair<String, UUID>, Pair<Try<String>, UUID>, NotUsed> flow =
                Flow.<Pair<String, UUID>>create()
                        .map(elem -> new Pair<Try<String>, UUID>(Success.apply(elem.first()), elem.second()));

        final BidiFlow<Pair<String, UUID>, Pair<String, UUID>, Pair<Try<String>, UUID>, Pair<Try<String>, UUID>,
                NotUsed> retry = RetryBidi.create(3L);

        final CompletionStage<List<Try<String>>> result =
                Source.from(Arrays.asList("a", "b", "c"))
                        .map(s -> new Pair<>(s, UUID.randomUUID()))
                        .via(retry.join(flow))
                        .map(Pair::first)
                        .runWith(Sink.seq(), mat);

        final List<Try<String>> expected = Arrays.asList(Success.apply("a"), Success.apply("b"), Success.apply("c"));
        List<Try<String>> actual = result.toCompletableFuture().get();
        assertEquals(actual.size(), 3);
        assertTrue(actual.containsAll(expected), "Did not get the expected elements from retry stage");
    }

    @Test
    public void testRetryBidiWithUniqueIdMapper() throws ExecutionException, InterruptedException {

        class MyContext {
            private String s;
            private UUID uuid;

            public MyContext(String s, UUID uuid) {
                this.s = s;
                this.uuid = uuid;
            }
        }
        final Flow<Pair<String, MyContext>, Pair<Try<String>, MyContext>, NotUsed> bottom =
                Flow.<Pair<String, MyContext>>create()
            .map(elem -> {
                if (elem.first().equals("b"))
                    return new Pair<>(failure, elem.second());
                else
                    return new Pair<>(Success.apply(elem.first()), elem.second());
            });

        Function<MyContext, Optional<Object>> uniqueIdMapper = context -> Optional.of(context.uuid);
        final BidiFlow<Pair<String, MyContext>, Pair<String, MyContext>, Pair<Try<String>, MyContext>,
                Pair<Try<String>, MyContext>, NotUsed> retryFlow =
                RetryBidi.create(3L, uniqueIdMapper, OverflowStrategy.backpressure());

        final CompletionStage<List<Try<String>>> result =
                Source.from(Arrays.asList("a", "b", "c"))
                        .map(s -> new Pair<>(s, new MyContext("dummy", UUID.randomUUID())))
                        .via(retryFlow.join(bottom))
                        .map(t -> t.first())
                        .runWith(Sink.seq(), mat);

        final List<Try<String>> expected = Arrays.asList(Success.apply("a"), Success.apply("c"), failure);
        assertTrue(result.toCompletableFuture().get().containsAll(expected),
                "Did not get the expected elements from retry stage");
    }

    @Test
    public void testRetryBidiWithFailureDecider() throws Exception {
        final Flow<Pair<String, UUID>, Pair<Try<String>, UUID>, NotUsed> bottom =
                Flow.<Pair<String, UUID>>create()
                        .map(elem -> new Pair<Try<String>, UUID>(Success.apply(elem.first()), elem.second()));

        final Function<Try<String>, Boolean> failureDecider =
                out -> out.isFailure() || out.equals(Success.apply("a")); // treat "a" as a failure for retry

        final BidiFlow<Pair<String, UUID>, Pair<String, UUID>, Pair<Try<String>, UUID>,
                Pair<Try<String>, UUID>, NotUsed> retry = RetryBidi.create(1L,
                Optional.of(failureDecider), OverflowStrategy.backpressure());

        Source.from(Arrays.asList("a", "b"))
                .map(s -> new Pair<>(s, UUID.randomUUID()))
                .via(retry.join(bottom))
                .map(t -> t.first())
                .runWith(TestSink.probe(system), mat)
                .request(2)
                .expectNext(Success.apply("a"), Success.apply("b"));
    }

    @Test
    public void testRetryBidiWithDelay() throws Exception {
        final Flow<Pair<String, UUID>, Pair<Try<String>, UUID>, NotUsed> bottom = Flow.<Pair<String, UUID>>create()
                .map(elem -> {
                    if (elem.first().equals("b"))
                        return new Pair<>(failure, elem.second());
                    else
                        return new Pair<>(Success.apply(elem.first()), elem.second());
                });

        final RetrySettings retrySettings =
                RetrySettings.<String, Try<String>, UUID>create(3L)
                        .withDelay(Duration.create(1, "seconds"));

        final BidiFlow<Pair<String, UUID>, Pair<String, UUID>, Pair<Try<String>, UUID>,
                Pair<Try<String>, UUID>, NotUsed> retry = RetryBidi.create(retrySettings);

        Source.from(Arrays.asList("a", "b", "c"))
                .map(s -> new Pair<>(s, UUID.randomUUID()))
                .via(retry.join(bottom))
                .map(t -> t.first())
                .runWith(TestSink.probe(system), mat)
                .request(3)
                .expectNext(Success.apply("a"), Success.apply("c"))
                .expectNoMsg(Duration.create(3, TimeUnit.SECONDS))
                .expectNext(failure);
    }

    @Test
    public void testRetryBidiWithBackoffAndMaxDelay() throws Exception {
        final Flow<Pair<String, UUID>, Pair<Try<String>, UUID>, NotUsed> bottom = Flow.<Pair<String, UUID>>create()
                .map(elem -> {
                    if (elem.first().equals("b"))
                        return new Pair<>(failure, elem.second());
                    else
                        return new Pair<>(Success.apply(elem.first()), elem.second());
                });

        final RetrySettings retrySettings = RetrySettings.<String, Try<String>, UUID>create(3L)
                .withDelay(Duration.create(1, "second"))
                .withExponentialBackoff(2.0)
                .withMaxDelay(Duration.create(2, "seconds"));

        final BidiFlow<Pair<String, UUID>, Pair<String, UUID>, Pair<Try<String>, UUID>,
                Pair<Try<String>, UUID>, NotUsed> retry = RetryBidi.create(retrySettings);

        Source.from(Arrays.asList("a", "b", "c"))
                .map(s -> new Pair<>(s, UUID.randomUUID()))
                .via(retry.join(bottom))
                .map(t -> t.first())
                .runWith(TestSink.probe(system), mat)
                .request(3)
                .expectNext(Success.apply("a"), Success.apply("c"))
                .expectNoMsg(Duration.create(5, TimeUnit.SECONDS)) //1s + 2s + 2s
                .expectNext(failure);
    }

    @Test
    public void testRetryBidiWithMapperDecider() throws ExecutionException, InterruptedException {

        class MyContext {
            private String s;
            private UUID uuid;

            public MyContext(String s, UUID uuid) {
                this.s = s;
                this.uuid = uuid;
            }
        }
        final Flow<Pair<String, MyContext>, Pair<Try<String>, MyContext>, NotUsed> bottom =
                Flow.<Pair<String, MyContext>>create()
                        .map(elem -> {
                            if (elem.first().equals("b"))
                                return new Pair<>(failure, elem.second());
                            else
                                return new Pair<>(Success.apply(elem.first()), elem.second());
                        });

        final Function<Try<String>, Boolean> failureDecider =
                out -> out.isFailure() || out.equals(Success.apply("a")); // treat "a" as a failure for retry

        final Function<MyContext, Optional<Object>> uniqueIdMapper = context -> Optional.of(context.uuid);
        final BidiFlow<Pair<String, MyContext>, Pair<String, MyContext>, Pair<Try<String>, MyContext>,
                Pair<Try<String>, MyContext>, NotUsed> retryFlow =
                RetryBidi.create(3L, uniqueIdMapper, Optional.of(failureDecider),
                        OverflowStrategy.backpressure());

        final CompletionStage<List<Try<String>>> result =
                Source.from(Arrays.asList("a", "b", "c"))
                        .map(s -> new Pair<>(s, new MyContext("dummy", UUID.randomUUID())))
                        .via(retryFlow.join(bottom))
                        .map(t -> t.first())
                        .runWith(Sink.seq(), mat);

        final List<Try<String>> expected = Arrays.asList(Success.apply("a"), Success.apply("c"), failure);
        assertTrue(result.toCompletableFuture().get().containsAll(expected),
                "Did not get the expected elements from retry stage");
    }

}
