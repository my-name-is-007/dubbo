/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author wangzongyao on 2022/2/14
 */
public interface UserService {

    UserEntity getByName(String name);

    List<UserEntity> all();

    void deleteByName(String name);

    void clear();

    String callbackTest(CallbackService callbackService);

    CompletableFuture<String> sayHello(String name);

}