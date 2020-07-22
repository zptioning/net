/**
 * Copyright (c) 2016-present, RxJava Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.rxjava3.internal.operators.observable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.core.Scheduler.Worker;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.internal.disposables.DisposableHelper;
import io.reactivex.rxjava3.internal.schedulers.TrampolineScheduler;

public final class ObservableInterval extends Observable<Long> {
    final Scheduler scheduler;
    final long initialDelay;
    final long period;
    final TimeUnit unit;

    /**
     * zp add
     * @param initialDelay
     * @param period
     * @param unit
     * @param scheduler
     */
    public ObservableInterval(long initialDelay, long period, TimeUnit unit, Scheduler scheduler) {
        this.initialDelay = initialDelay;
        this.period = period;
        this.unit = unit;
        this.scheduler = scheduler;
    }

    /**
     * zp add
     * @param observer the incoming {@code Observer}, never {@code null}
     *                 subscribe() 入参
     */
    @Override
    public void subscribeActual(Observer<? super Long> observer) {
        IntervalObserver is = new IntervalObserver(observer);
        observer.onSubscribe(is);

        Scheduler sch = scheduler;

        if (sch instanceof TrampolineScheduler) {
            Worker worker = sch.createWorker();
            is.setResource(worker);
            worker.schedulePeriodically(is, initialDelay, period, unit);
        } else {
            /* zp add 隔段时间执行一次操作 */
            Disposable d = sch.schedulePeriodicallyDirect(is, initialDelay, period, unit);
            // zp add 设置内部 disposable
            is.setResource(d);
        }
    }

    static final class IntervalObserver
    extends AtomicReference<Disposable>
    implements Disposable, Runnable {

        private static final long serialVersionUID = 346773832286157679L;

        /* zp add subscribe() 方法的入参 */
        final Observer<? super Long> downstream;

        long count;

        IntervalObserver(Observer<? super Long> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void dispose() {
            // zp add 取消内部 disposable
            DisposableHelper.dispose(this);
        }

        @Override
        public boolean isDisposed() {
            return get() == DisposableHelper.DISPOSED;
        }

        @Override
        public void run() {
            if (get() != DisposableHelper.DISPOSED) {
                downstream.onNext(count++);
            }
        }

        public void setResource(Disposable d) {
            /* zp add 只设置一次 */
            DisposableHelper.setOnce(this, d);
        }
    }
}
