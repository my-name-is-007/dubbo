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

/**
 * 你可以理解为任务的上下文: TimerTask 与 Timer 沟通的桥梁, 关联任务 与 时间轮.
 *
 * 体系的设计是值得借鉴的: 有任务、有执行器, 如果需要的话, 还要有一个 上下文.
 */
public interface Timeout {

    /** 关联的Timer: 调度器(实现为 时间轮). **/
    Timer timer();

    /** 关联的任务: TimerTask. **/
    TimerTask task();

    /** 关联的任务是否过期. **/
    boolean isExpired();

    /** 关联的任务是否被取消. **/
    boolean isCancelled();

    /** 取消关联的任务. **/
    boolean cancel();
}