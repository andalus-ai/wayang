package tech.kayys.wayang.cel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * Resolves compiler/evaluator implementations by an exact provider identity.
 *
 * <p>There is deliberately no "first provider wins" behavior. A provider is
 * usable only when both SPI halves are present for the same id and version.
 * This makes class-path ordering irrelevant and prevents accidental runtime
 * substitution after an artifact has been compiled.</p>
 */
public final class CelProviderRegistry {
    private final Map<ProviderKey, CelCompilerProvider> compilers;
    private final Map<ProviderKey, CelEvaluatorProvider> evaluators;

    private CelProviderRegistry(Collection<? extends CelCompilerProvider> compilerProviders,
            Collection<? extends CelEvaluatorProvider> evaluatorProviders) {
        this.compilers = indexCompilers(compilerProviders);
        this.evaluators = indexEvaluators(evaluatorProviders);
        if (!compilers.keySet().equals(evaluators.keySet())) {
            throw new IllegalArgumentException("CEL compiler/evaluator provider identities must match; "
                    + "compiler-only=" + difference(compilers.keySet(), evaluators.keySet())
                    + ", evaluator-only=" + difference(evaluators.keySet(), compilers.keySet()));
        }
    }

    public static CelProviderRegistry of(Collection<? extends CelCompilerProvider> compilers,
            Collection<? extends CelEvaluatorProvider> evaluators) {
        return new CelProviderRegistry(Objects.requireNonNull(compilers, "compilers"),
                Objects.requireNonNull(evaluators, "evaluators"));
    }

    /** Discover providers through Java SPI and validate the complete provider set. */
    public static CelProviderRegistry discover(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        List<CelCompilerProvider> compilers = ServiceLoader.load(CelCompilerProvider.class, classLoader)
                .stream().map(ServiceLoader.Provider::get).toList();
        List<CelEvaluatorProvider> evaluators = ServiceLoader.load(CelEvaluatorProvider.class, classLoader)
                .stream().map(ServiceLoader.Provider::get).toList();
        return of(compilers, evaluators);
    }

    public CelCompilerProvider requireCompiler(String providerId, String providerVersion) {
        ProviderKey key = new ProviderKey(providerId, providerVersion);
        CelCompilerProvider provider = compilers.get(key);
        if (provider == null) {
            throw new IllegalArgumentException("No CEL compiler provider registered for " + key);
        }
        return provider;
    }

    public CelEvaluatorProvider requireEvaluator(String providerId, String providerVersion) {
        ProviderKey key = new ProviderKey(providerId, providerVersion);
        CelEvaluatorProvider provider = evaluators.get(key);
        if (provider == null) {
            throw new IllegalArgumentException("No CEL evaluator provider registered for " + key);
        }
        return provider;
    }

    /** Stable, sorted identities suitable for diagnostics and health reporting. */
    public List<ProviderKey> providers() {
        return compilers.keySet().stream().sorted(Comparator.comparing(ProviderKey::providerId)
                .thenComparing(ProviderKey::providerVersion)).toList();
    }

    private static Map<ProviderKey, CelCompilerProvider> indexCompilers(
            Collection<? extends CelCompilerProvider> providers) {
        Map<ProviderKey, CelCompilerProvider> indexed = new TreeMap<>(Comparator
                .comparing(ProviderKey::providerId).thenComparing(ProviderKey::providerVersion));
        for (CelCompilerProvider provider : providers) {
            Objects.requireNonNull(provider, "compiler provider");
            ProviderKey key = new ProviderKey(provider.providerId(), provider.providerVersion());
            if (indexed.putIfAbsent(key, provider) != null) {
                throw new IllegalArgumentException("Duplicate CEL compiler provider: " + key);
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<ProviderKey, CelEvaluatorProvider> indexEvaluators(
            Collection<? extends CelEvaluatorProvider> providers) {
        Map<ProviderKey, CelEvaluatorProvider> indexed = new TreeMap<>(Comparator
                .comparing(ProviderKey::providerId).thenComparing(ProviderKey::providerVersion));
        for (CelEvaluatorProvider provider : providers) {
            Objects.requireNonNull(provider, "evaluator provider");
            ProviderKey key = new ProviderKey(provider.providerId(), provider.providerVersion());
            if (indexed.putIfAbsent(key, provider) != null) {
                throw new IllegalArgumentException("Duplicate CEL evaluator provider: " + key);
            }
        }
        return Map.copyOf(indexed);
    }

    private static <T> List<T> difference(Collection<T> left, Collection<T> right) {
        List<T> values = new ArrayList<>(left);
        values.removeAll(right);
        return values.stream().sorted(Comparator.comparing(Object::toString)).toList();
    }

    public record ProviderKey(String providerId, String providerVersion) {
        public ProviderKey {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("providerId must not be blank");
            }
            if (providerVersion == null || providerVersion.isBlank()) {
                throw new IllegalArgumentException("providerVersion must not be blank");
            }
        }
    }
}
