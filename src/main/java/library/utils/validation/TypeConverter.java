package library.utils.validation;

/**
 * Converts K class to V class
 * @param <K> original class
 * @param <V> produced class
 */
@FunctionalInterface
public interface TypeConverter<K, V> {
    /**
     * @param k instance of Class K to be converted to class V
     * @return converted to class V instance
     */
    V process(K k);
}
