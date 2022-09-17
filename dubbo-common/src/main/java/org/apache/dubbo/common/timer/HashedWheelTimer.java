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

    /** 时间轮对象个数, 创建了多少个实例对象了: 创建太多的话, 要报错的. **/
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    /** 是否已创建太多实例: 和上面那个属性联动的. **/
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();

    /** 实例对象个数限制在 64个以内. **/
    private static final int INSTANCE_COUNT_LIMIT = 64;

    /** 状态更新器. **/
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    /** 时间轮的逻辑. **/
    private final Worker worker = new Worker();

    /** 执行Worker逻辑的线程. **/
    private final Thread workerThread;

    /** 时间轮状态. **/
    private static final int WORKER_STATE_INIT = 0;
    private static final int WORKER_STATE_STARTED = 1;
    private static final int WORKER_STATE_SHUTDOWN = 2;

    /**
     * 时间轮状态:
     *     0 - init
     *     1 - started
     *     2 - shut down
     */
    private volatile int workerState;
    
    /** 时间指针每次加1所代表的实际时间(纳秒): 槽与槽之间的时间间隔. **/
    private final long tickDuration;
    
    /** 槽位数组. **/
    private final HashedWheelBucket[] wheel;
    
    /** wheel.length - 1, 与运算确定任务所属槽位: 较为常见的一种操作, 如 Disruptor、经典莫过于HashMap. **/
    private final int mask;
    
    /** 时间轮中的startTime的闭锁. **/
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    /** 定时任务集合: 加入的定时任务先放到这里, 再由工作线程从中拉取到对应的Bucket中. **/
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();

    /** 暂存取消的定时任务 会被销毁掉. **/
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();

    /** 当前时间轮剩余的定时任务总数. **/
    private final AtomicLong pendingTimeouts = new AtomicLong(0);

    /** 当前 时间轮 中 最大任务数. **/
    private final long maxPendingTimeouts;

    /** 时间轮启动的时间: 提交 到 时间轮 的 定时任务 deadline字段值 以该时间为起点 进行计算. **/
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
     * @param threadFactory      创建线程, 执行 时间轮的逻辑(不是执行 TimerTask 啊)
     * @param tickDuration       槽位之间的时间间隔
     * @param unit               时间单位
     * @param ticksPerWheel      槽位个数
     * @param maxPendingTimeouts 时间轮 最大任务数
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel, long maxPendingTimeouts) {
        if (threadFactory == null) { throw new NullPointerException("threadFactory"); }
        if (unit == null) { throw new NullPointerException("unit"); }
        if (tickDuration <= 0) { throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration); }
        if (ticksPerWheel <= 0) { throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel); }

        //初始化 槽位数组
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        //计算间隔, 转 纳秒
        this.tickDuration = unit.toNanos(tickDuration);
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) { throw new IllegalArgumentException(String.format("tickDuration: %d (expected: 0 < tickDuration in nanos < %d", tickDuration, Long.MAX_VALUE / wheel.length)); }

        //创建 指定 工作线程
        workerThread = threadFactory.newThread(worker);

        this.maxPendingTimeouts = maxPendingTimeouts;

        //时间轮实例对象创建过多
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

    /** 创建指定长度的槽位数组. **/
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) { throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel); }
        if (ticksPerWheel > 1073741824) { throw new IllegalArgumentException("ticksPerWheel may not be greater than 2^30: " + ticksPerWheel); }

        //计算出 大于等于指定值 且是 2的N次方 的 一个数值
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    /** 看得出来, 不能说和HashMap毫无关系吧, 至少也是 一模一样. **/
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /** 启动 时间轮. **/
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            //初始化状态, 设为 已启动, 并启动工作线程: 工作线程 会执行 Worker逻辑
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

        //确保 工作线程 确实已经 启动了, 否则添加任务也得不到调度
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
            //有些暴力啊: 只要没停, 就一直中断工作线程. 为了让它执行, 用 join让它加进来.
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
            //实例数 - 1
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
     * 注意: 添加任务时才启动时间轮, 没任务你特码让它跑着干嘛. 其实也对, 线程池也是这样的, 有任务才创建线程嘛.
     * @param task
     * @param delay
     * @param unit
     * @return
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) { throw new NullPointerException("task"); }
        if (unit == null) { throw new NullPointerException("unit"); }

        //+1后的任务数
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        //任务超了
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        //开启时间轮
        start();

        //任务的执行时间: 相对于 时间轮开始的时间
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        //Guard against overflow.
        if (delay > 0 && deadline < 0) { deadline = Long.MAX_VALUE; }

        //创建 任务 上下文
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

        /** 尚未被执行的任务: 线程从 时间轮的timeouts队列中拉取, 如果未被取消, 就加进去. **/
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        /** 当前是第几圈: 从 0 开始. **/
        private long tick;

        @Override
        public void run() {
            //初始化 开始时间
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            //唤醒 主线程
            startTimeInitialized.countDown();

            //只要 时间轮还在执行, 就一直搞
            do {
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    //当前圈数, 对应 槽位数组下标
                    int idx = (int) (tick & mask);

                    //将 cancelledTimeouts 队列 任务 移除掉
                    processCancelledTasks();

                    //定位到对应槽位
                    HashedWheelBucket bucket = wheel[idx];

                    //将 任务队列 的 任务, 加到 对应槽位
                    transferTimeoutsToBuckets();

                    //执行当前槽位的任务
                    bucket.expireTimeouts(deadline);

                    //圈数 + 1
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            //Fill the unprocessedTimeouts so we can return them from stop() method.
            //下面两个循环没看
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

        /** 从 timeouts 拉任务, 加到对应槽位上去. **/
        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            for (int i = 0; i < 100000; i++) {
                //任务队列 拉取 任务
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) { break; }

                //取消就不看了
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) { continue; }

                //时间指针 要 动几下
                long calculated = timeout.deadline / tickDuration;

                //动的这几下相当于几圈
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                //Ensure we don't schedule for past.
                final long ticks = Math.max(calculated, tick);
                int stopIndex = (int) (ticks & mask);

                //给任务干到对应槽位
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
            //多长时间后, 时间轮指针 移动 至 下一格
            long deadline = tickDuration * (tick + 1);

            for (; ; ) {
                //当前相对时间
                final long currentTime = System.nanoTime() - startTime;
                //需要睡多少: 加了999999, 要保证执行时间不能提前(想下整型的取值)
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                //出他妈问题了
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                //windows下抹去个位: 取0
                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    //睡它
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
     * 槽位上的任务.
     * 其实我起初想的是, 通过 链表等现成的队列去直接实现,
     * 但没想到的是任务自身就是Node, 有前驱有后继.
     */
    private static final class HashedWheelTimeout implements Timeout {

        /** 任务状态. **/
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;

        /** 用于状态控制. **/
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        /** 关联的时间轮. **/
        private final HashedWheelTimer timer;

        /** 实际被调度的任务. **/
        private final TimerTask task;

        /**
         * 任务的执行时间: 相对于 时间轮 start 的 时间.
         * 计算方式: currentTime(创建 HashedWheelTimeout 的时间) + delay(任务延迟时间) - startTime(HashedWheelTimer 的启动时间)
         */
        private final long deadline;

        /** 当前任务的状态: 初始化、被取消、已执行(但不确定执没执行完). **/
        private volatile int state = ST_INIT;

        /**
         * 当前任务剩余的时钟周期数.
         *
         * 时间轮表示的时间长度有限, 在任务到期时间与当前时刻的时间差,
         * 超过时间轮单圈能表示的时长, 就出现套圈的情况，这时需要该字段表示剩余的时钟周期
         */
        long remainingRounds;

        /**
         * 当前任务 的 前驱、后继
         */
        HashedWheelTimeout prev;
        HashedWheelTimeout next;

        /** 所属的 Bucket. **/
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        /**
         * 将任务取消执行.
         *
         * 取消时仅仅是修改状态而已, 删除是在下一次轮询到的时候.
         *
         * 被取消的任务会被放入已取消的队列
         */
        @Override
        public boolean cancel() {
            //先改状态: 先占用, 别的再他妈说
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }

            //加入 已取消队列
            timer.cancelledTimeouts.add(this);
            return true;
        }

        /** 执行当前任务. **/
        public void expire() {
            //先改状态
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) { return; }

            //执行任务
            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        /** 从 Bucket中移除当前任务. **/
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        /** 查看状态. **/
        public int state() { return state; }
        @Override
        public boolean isCancelled() { return state() == ST_CANCELLED; }
        @Override
        public boolean isExpired() { return state() == ST_EXPIRED; }

        /** 设置状态. **/
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
     * 槽位, 时间轮上的槽, 槽后面挂接任务节点
     */
    private static final class HashedWheelBucket {

        /** 首尾 任务. **/
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /** 添加任务: 链表操作. **/
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

        /** 执行当前槽位上的所有任务: 可执行就执行, 不可就进行相关操作(取消或轮数减一). **/
        void expireTimeouts(long deadline) {
            //待执行的节点
            HashedWheelTimeout timeoutToRun = head;

            while (timeoutToRun != null) {
                HashedWheelTimeout next = timeoutToRun.next;

                //待执行轮数小于0
                if (timeoutToRun.remainingRounds <= 0) {
                    //将当前节点移除, 并 返回下一节点
                    next = remove(timeoutToRun);

                    //当前节点可执行
                    if (timeoutToRun.deadline <= deadline) {
                        timeoutToRun.expire();
                    } else {
                        //任务放错槽位了: 防御性判断而已, 不可能发生
                        throw new IllegalStateException(String.format("timeout.deadline (%d) > deadline (%d)", timeoutToRun.deadline, deadline));
                    }
                //当前节点被取消了: 移除掉
                } else if (timeoutToRun.isCancelled()) {
                    next = remove(timeoutToRun);
                //还未到时间: 轮数减一
                } else {
                    timeoutToRun.remainingRounds--;
                }
                //next 设为 待检查节点
                timeoutToRun = next;
            }
        }

        /** 从 当前槽位 移除 指定任务, 并返回其下一节点. **/
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
         * 取出链表中所有任务, 将 未取消且未执行的加入至 参数set中
         *
         * 我特么搞不懂为什么不直接判断是否为 初始化状态.
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) { return; }
                if (timeout.isExpired() || timeout.isCancelled()) { continue; }

                set.add(timeout);
            }
        }

        /** 从槽位中取出第一个任务. **/
        private HashedWheelTimeout pollTimeout() {
            //找到 head: head为空 返回null
            HashedWheelTimeout head = this.head;
            if (head == null) { return null; }

            HashedWheelTimeout next = head.next;
            //头结点 后继 为null: 取出头结点后链表就空了
            if (next == null) {
                tail = this.head = null;

            //头节点有后继: 将后继设为头节点
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
