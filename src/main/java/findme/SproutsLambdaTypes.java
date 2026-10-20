package findme;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/// Native image only. Sprouts types a tuple by its first item's class and allocates arrays of that type; swing-tree
/// adds lambdas as listeners, and native image can't have arrays of lambda classes (registering one crashes the
/// builder in `LambdaUtils.findStableLambdaName`), so the window failed with a `MissingReflectionRegistrationError`.
/// This replaces `sprouts.impl.Util.expectedClassFromItem` (sprouts 2.8.0) with the same logic, except that a
/// lambda's type is its functional interface. Recheck when updating sprouts. `sprouts.Result` has its own unpatched
/// private copy of the same method; harmless today, as no `Result` holds lambdas.
@TargetClass(className = "sprouts.impl.Util")
final class SproutsLambdaTypes {
    @Substitute
    @SuppressWarnings("unchecked")
    static <T> Class<T> expectedClassFromItem(T item) {
        Class<?> type = item.getClass();
        if (type.isHidden() && type.getInterfaces().length > 0) return (Class<T>) type.getInterfaces()[0];
        // as the original: an enum constant with a body is an anonymous subclass of its enum
        if (Enum.class.isAssignableFrom(type) && type.getSuperclass() != null && !type.getSuperclass().equals(Enum.class))
            return (Class<T>) type.getSuperclass();
        return (Class<T>) type;
    }
}
