package com.communi.suggestu.placitum.util;

import java.util.concurrent.Callable;

public record ValueCallable<T>(T value) implements Callable<T> {
    @Override
    public T call() {
        return value;
    }
}
