package top.forye.spe.model;

public class Pair<T1, T2> {
    private final T1 _first;
    private final T2 _second;

    public static <T1, T2> Pair<T1, T2> of(T1 first, T2 second) {
        return new Pair<>(first, second);
    }

    public Pair(T1 first, T2 second) {
        this._first = first;
        this._second = second;
    }

    public T1 first() {
        return this._first;
    }

    public T2 second() {
        return this._second;
    }
}
