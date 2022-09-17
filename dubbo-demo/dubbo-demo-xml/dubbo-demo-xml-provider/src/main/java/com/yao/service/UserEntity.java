/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.service;

import java.io.Serializable;

/**
 * @author wangzongyao on 2022/2/14
 */
public class UserEntity implements Serializable {

    private String name;
    private Integer age;
    private String motto;

    public UserEntity(){ }

    public UserEntity(String name, Integer age, String motto) {
        this.name = name;
        this.age = age;
        this.motto = motto;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getMotto() {
        return motto;
    }

    public void setMotto(String motto) {
        this.motto = motto;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("UserEntity{");
        sb.append("name='").append(name).append('\'');
        sb.append(", age=").append(age);
        sb.append(", motto='").append(motto).append('\'');
        sb.append('}');
        return sb.toString();
    }

}