package stryker4jvm.core.model;

public interface Collector<T extends AST> {
    CollectedMutants<T> collect(T tree);
}