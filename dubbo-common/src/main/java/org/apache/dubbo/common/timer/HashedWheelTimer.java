/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.timer;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    /**
     * may be in spi?
     */
    public static final String NAME = "hased";

    private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);

    /** ?????????????????????, ?????????????????????????????????: ??????????????????, ????????????. **/
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    /** ???????????????????????????: ??????????????????????????????. **/
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();

    /** ??????????????????????????? 64?????????. **/
    private static final int INSTANCE_COUNT_LIMIT = 64;

    /** ???????????????. **/
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    /** ??????????????????. **/
    private final Worker worker = new Worker();

    /** ??????Worker???????????????. **/
    private final Thread workerThread;

    /** ???????????????. **/
    private static final int WORKER_STATE_INIT = 0;
    private static final int WORKER_STATE_STARTED = 1;
    private static final int WORKER_STATE_SHUTDOWN = 2;

    /**
     * ???????????????:
     *     0 - init
     *     1 - started
     *     2 - shut down
     */
    private volatile int workerState;
    
    /** ?????????????????????1????????????????????????(??????): ??????????????????????????????. **/
    private final long tickDuration;
    
    /** ????????????. **/
    private final HashedWheelBucket[] wheel;
    
    /** wheel.length - 1, ?????????????????????????????????: ???????????????????????????, ??? Disruptor??????????????????HashMap. **/
    private final int mask;
    
    /** ???????????????startTime?????????. **/
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    /** ??????????????????: ????????????????????????????????????, ??????????????????????????????????????????Bucket???. **/
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();

    /** ??????????????????????????? ???????????????. **/
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();

    /** ??????????????????????????????????????????. **/
    private final AtomicLong pendingTimeouts = new AtomicLong(0);

    /** ?????? ????????? ??? ???????????????. **/
    private final long maxPendingTimeouts;

    /** ????????????????????????: ?????? ??? ????????? ??? ???????????? deadline????????? ????????????????????? ????????????. **/
    private volatile long startTime;

    public HashedWheelTimer() { this(Executors.defaultThreadFactory()); }
    public HashedWheelTimer(long tickDuration, TimeUnit unit) { this(Executors.defaultThreadFactory(), tickDuration, unit); }
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) { this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel); }
    public HashedWheelTimer(ThreadFactory threadFactory) { this(threadFactory, 100, TimeUnit.MILLISECONDS); }
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit) { this(threadFactory, tickDuration, unit, 512); }
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel) { this(threadFactory, tickDuration, unit, ticksPerWheel, -1); }

    /**
     * Creates a new timer.
     *
     * @param threadFactory      ????????????, ?????? ??????????????????(???????????? TimerTask ???)
     * @param tickDuration       ???????????????????????????
     * @param unit               ????????????
     * @param ticksPerWheel      ????????????
     * @param maxPendingTimeouts ????????? ???????????????
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel, long maxPendingTimeouts) {
        if (threadFactory == null) { throw new NullPointerException("threadFactory"); }
        if (unit == null) { throw new NullPointerException("unit"); }
        if (tickDuration <= 0) { throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration); }
        if (ticksPerWheel <= 0) { throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel); }

        //????????? ????????????
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        //????????????, ??? ??????
        this.tickDuration = unit.toNanos(tickDuration);
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) { throw new IllegalArgumentException(String.format("tickDuration: %d (expected: 0 < tickDuration in nanos < %d", tickDuration, Long.MAX_VALUE / wheel.length)); }

        //?????? ?????? ????????????
        workerThread = threadFactory.newThread(worker);

        this.maxPendingTimeouts = maxPendingTimeouts;

        //?????????????????????????????????
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    /** ?????????????????????????????????. **/
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) { throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel); }
        if (ticksPerWheel > 1073741824) { throw new IllegalArgumentException("ticksPerWheel may not be greater than 2^30: " + ticksPerWheel); }

        //????????? ????????????????????? ?????? 2???N?????? ??? ????????????
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    /** ????????????, ????????????HashMap???????????????, ???????????? ????????????. **/
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /** ?????? ?????????. **/
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            //???????????????, ?????? ?????????, ?????????????????????: ???????????? ????????? Worker??????
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        //?????? ???????????? ???????????? ?????????, ????????????????????????????????????
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }


        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            //???????????????: ????????????, ???????????????????????????. ??????????????????, ??? join???????????????.
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            //????????? - 1
            INSTANCE_COUNTER.decrementAndGet();
        }
        return worker.unprocessedTimeouts();
    }

    @Override
    public boolean isStop() {
        return WORKER_STATE_SHUTDOWN == WORKER_STATE_UPDATER.get(this);
    }

    /**
     *
     * ??????: ?????????????????????????????????, ????????????????????????????????????. ????????????, ????????????????????????, ???????????????????????????.
     * @param task
     * @param delay
     * @param unit
     * @return
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) { throw new NullPointerException("task"); }
        if (unit == null) { throw new NullPointerException("unit"); }

        //+1???????????????
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        //????????????
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        //???????????????
        start();

        //?????????????????????: ????????? ????????????????????????
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        //Guard against overflow.
        if (delay > 0 && deadline < 0) { deadline = Long.MAX_VALUE; }

        //?????? ?????? ?????????
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        String resourceType = ClassUtils.simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    private final class Worker implements Runnable {

        /** ????????????????????????: ????????? ????????????timeouts???????????????, ??????????????????, ????????????. **/
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        /** ??????????????????: ??? 0 ??????. **/
        private long tick;

        @Override
        public void run() {
            //????????? ????????????
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            //?????? ?????????
            startTimeInitialized.countDown();

            //?????? ?????????????????????, ????????????
            do {
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    //????????????, ?????? ??????????????????
                    int idx = (int) (tick & mask);

                    //??? cancelledTimeouts ?????? ?????? ?????????
                    processCancelledTasks();

                    //?????????????????????
                    HashedWheelBucket bucket = wheel[idx];

                    //??? ???????????? ??? ??????, ?????? ????????????
                    transferTimeoutsToBuckets();

                    //???????????????????????????
                    bucket.expireTimeouts(deadline);

                    //?????? + 1
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            //Fill the unprocessedTimeouts so we can return them from stop() method.
            //????????????????????????
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) { break; }
                if (!timeout.isCancelled()) { unprocessedTimeouts.add(timeout); }
            }
            processCancelledTasks();
        }

        /** ??? timeouts ?????????, ????????????????????????. **/
        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            for (int i = 0; i < 100000; i++) {
                //???????????? ?????? ??????
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) { break; }

                //??????????????????
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) { continue; }

                //???????????? ??? ?????????
                long calculated = timeout.deadline / tickDuration;

                //??????????????????????????????
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                //Ensure we don't schedule for past.
                final long ticks = Math.max(calculated, tick);
                int stopIndex = (int) (ticks & mask);

                //???????????????????????????
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (; ; ) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         *
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        private long waitForNextTick() {
            //???????????????, ??????????????? ?????? ??? ?????????
            long deadline = tickDuration * (tick + 1);

            for (; ; ) {
                //??????????????????
                final long currentTime = System.nanoTime() - startTime;
                //???????????????: ??????999999, ?????????????????????????????????(?????????????????????)
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                //??????????????????
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                //windows???????????????: ???0
                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    //??????
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        Set<Timeout> unprocessedTimeouts() { return Collections.unmodifiableSet(unprocessedTimeouts); }
    }

    /**
     * ??????????????????.
     * ????????????????????????, ?????? ???????????????????????????????????????,
     * ????????????????????????????????????Node, ??????????????????.
     */
    private static final class HashedWheelTimeout implements Timeout {

        /** ????????????. **/
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;

        /** ??????????????????. **/
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        /** ??????????????????. **/
        private final HashedWheelTimer timer;

        /** ????????????????????????. **/
        private final TimerTask task;

        /**
         * ?????????????????????: ????????? ????????? start ??? ??????.
         * ????????????: currentTime(?????? HashedWheelTimeout ?????????) + delay(??????????????????) - startTime(HashedWheelTimer ???????????????)
         */
        private final long deadline;

        /** ?????????????????????: ?????????????????????????????????(???????????????????????????). **/
        private volatile int state = ST_INIT;

        /**
         * ????????????????????????????????????.
         *
         * ????????????????????????????????????, ????????????????????????????????????????????????,
         * ???????????????????????????????????????, ???????????????????????????????????????????????????????????????????????????
         */
        long remainingRounds;

        /**
         * ???????????? ??? ???????????????
         */
        HashedWheelTimeout prev;
        HashedWheelTimeout next;

        /** ????????? Bucket. **/
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        /**
         * ?????????????????????.
         *
         * ????????????????????????????????????, ???????????????????????????????????????.
         *
         * ????????????????????????????????????????????????
         */
        @Override
        public boolean cancel() {
            //????????????: ?????????, ??????????????????
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }

            //?????? ???????????????
            timer.cancelledTimeouts.add(this);
            return true;
        }

        /** ??????????????????. **/
        public void expire() {
            //????????????
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) { return; }

            //????????????
            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        /** ??? Bucket?????????????????????. **/
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        /** ????????????. **/
        public int state() { return state; }
        @Override
        public boolean isCancelled() { return state() == ST_CANCELLED; }
        @Override
        public boolean isExpired() { return state() == ST_EXPIRED; }

        /** ????????????. **/
        public boolean compareAndSetState(int expected, int state) { return STATE_UPDATER.compareAndSet(this, expected, state); }

        @Override
        public Timer timer() { return timer; }
        @Override
        public TimerTask task() { return task; }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;
            String simpleClassName = ClassUtils.simpleClassName(this.getClass());

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName)
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * ??????, ??????????????????, ???????????????????????????
     */
    private static final class HashedWheelBucket {

        /** ?????? ??????. **/
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /** ????????????: ????????????. **/
        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /** ????????????????????????????????????: ??????????????????, ???????????????????????????(?????????????????????). **/
        void expireTimeouts(long deadline) {
            //??????????????????
            HashedWheelTimeout timeoutToRun = head;

            while (timeoutToRun != null) {
                HashedWheelTimeout next = timeoutToRun.next;

                //?????????????????????0
                if (timeoutToRun.remainingRounds <= 0) {
                    //?????????????????????, ??? ??????????????????
                    next = remove(timeoutToRun);

                    //?????????????????????
                    if (timeoutToRun.deadline <= deadline) {
                        timeoutToRun.expire();
                    } else {
                        //?????????????????????: ?????????????????????, ???????????????
                        throw new IllegalStateException(String.format("timeout.deadline (%d) > deadline (%d)", timeoutToRun.deadline, deadline));
                    }
                //????????????????????????: ?????????
                } else if (timeoutToRun.isCancelled()) {
                    next = remove(timeoutToRun);
                //???????????????: ????????????
                } else {
                    timeoutToRun.remainingRounds--;
                }
                //next ?????? ???????????????
                timeoutToRun = next;
            }
        }

        /** ??? ???????????? ?????? ????????????, ????????????????????????. **/
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * ???????????????????????????, ??? ????????????????????????????????? ??????set???
         *
         * ??????????????????????????????????????????????????? ???????????????.
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) { return; }
                if (timeout.isExpired() || timeout.isCancelled()) { continue; }

                set.add(timeout);
            }
        }

        /** ?????????????????????????????????. **/
        private HashedWheelTimeout pollTimeout() {
            //?????? head: head?????? ??????null
            HashedWheelTimeout head = this.head;
            if (head == null) { return null; }

            HashedWheelTimeout next = head.next;
            //????????? ?????? ???null: ?????????????????????????????????
            if (next == null) {
                tail = this.head = null;

            //??????????????????: ????????????????????????
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }

    private boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win"); }
}
