/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.common.utils.StringUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * 请求模型类.
 */
public class Request {

    /** 负责生成请求Id, 查找对应 CompletableFuture时会用到. **/
    private static final AtomicLong INVOKE_ID = new AtomicLong(0);

    /** 请求编号: JVM 进程内唯一. **/
    private final long mId;

    /** dubbo版本. **/
    private String mVersion;

    /** 是否需要响应. **/
    private boolean mTwoWay = true;

    /** 是否是事件. **/
    private boolean mEvent = false;

    /** 是否是异常的请求. **/
    private boolean mBroken = false;

    /** 请求数据. **/
    private Object mData;

    public Request() { mId = newId(); }
    public Request(long id) { mId = id; }

    /** 负值 一样 用于 Id. **/
    private static long newId() { return INVOKE_ID.getAndIncrement(); }

    private static String safeToString(Object data) {
        if (data == null) {
            return null;
        }
        String dataStr;
        try {
            dataStr = data.toString();
        } catch (Throwable e) {
            dataStr = "<Fail toString of " + data.getClass() + ", cause: " +
                    StringUtils.toString(e) + ">";
        }
        return dataStr;
    }

    public long getId() {
        return mId;
    }

    public String getVersion() {
        return mVersion;
    }

    public void setVersion(String version) {
        mVersion = version;
    }

    public boolean isTwoWay() {
        return mTwoWay;
    }

    public void setTwoWay(boolean twoWay) {
        mTwoWay = twoWay;
    }

    public boolean isEvent() {
        return mEvent;
    }

    public void setEvent(String event) {
        this.mEvent = true;
        this.mData = event;
    }

    public void setEvent(boolean mEvent) {
        this.mEvent = mEvent;
    }

    public boolean isBroken() {
        return mBroken;
    }

    public void setBroken(boolean mBroken) {
        this.mBroken = mBroken;
    }

    public Object getData() {
        return mData;
    }

    public void setData(Object msg) {
        mData = msg;
    }

    public boolean isHeartbeat() {
        return mEvent && HEARTBEAT_EVENT == mData;
    }

    public void setHeartbeat(boolean isHeartbeat) {
        if (isHeartbeat) {
            setEvent(HEARTBEAT_EVENT);
        }
    }

    @Override
    public String toString() {
        return "Request [id=" + mId + ", version=" + mVersion + ", twoway=" + mTwoWay + ", event=" + mEvent
                + ", broken=" + mBroken + ", data=" + (mData == this ? "this" : safeToString(mData)) + "]";
    }
}
