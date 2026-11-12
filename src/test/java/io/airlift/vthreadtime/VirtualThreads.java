/*
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
package io.airlift.vthreadtime;

import java.util.concurrent.atomic.AtomicReference;

/// Runs test bodies on a virtual thread and rethrows what they throw.
public final class VirtualThreads
{
    private VirtualThreads() {}

    public static <T> T onVirtualThread(ThrowingSupplier<T> task)
            throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(task.get());
            }
            catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.join();
        switch (failure.get()) {
            case null -> {}
            case Exception exception -> throw exception;
            case Error error -> throw error;
            case Throwable other -> throw new IllegalStateException(other);
        }
        return result.get();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T>
    {
        T get()
                throws Throwable;
    }
}
