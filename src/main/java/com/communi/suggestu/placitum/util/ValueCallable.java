package com.communi.suggestu.placitum.util;

import java.util.Objects;
import java.util.concurrent.Callable;

@SuppressWarnings("ClassCanBeRecord")
public final class ValueCallable<T> implements Callable<T> {
    private final T value;

    public ValueCallable(T value) {
        this.value = value;
    }

    @Override
    public T call() {
        return value;
    }

    public T value() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        @SuppressWarnings("rawtypes") var that = (ValueCallable) obj;
        return Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "ValueCallable[" +
                "value=" + value + ']';
    }

}
