package io.paradaux.treasury.mappers;

/** Internal insert parameter: only the database-generated key is mutable. */
public final class GeneratedKey<T> {
    private final T value;
    private long id;

    public GeneratedKey(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }
}
