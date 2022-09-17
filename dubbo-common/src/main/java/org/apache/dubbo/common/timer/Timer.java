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

import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Schedules {@link TimerTask}s for one-time future execution in a background
 * thread.
 */
public interface Timer {

    /**
     * 在指定延迟后, 运行指定的任务. 并返回任务的上下文.
     *
     * @throws IllegalStateException      时间轮已经停止
     * @throws RejectedExecutionException 任务太多
     */
    Timeout newTimeout(TimerTask task, long delay, TimeUnit unit);

    /**
     * 取消未被执行的任务, 并 返回.
     */
    Set<Timeout> stop();

    /** 时间轮是否已停止. **/
    boolean isStop();
}