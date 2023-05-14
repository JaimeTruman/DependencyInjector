package es.dependencyinjector.dependencies;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static es.jaime.javaddd.application.utils.CollectionUtils.*;
import static es.jaime.javaddd.application.utils.ReflectionUtils.*;

public final class InMemoryDependenciesRepository implements DependenciesRepository {
    private final Map<Class<?>, Object> instances = new ConcurrentHashMap<>();

    private final Map<Class<? extends Annotation>, List<Object>> annotatedWithIndex = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Object>> implementsInterfaceIndex = new ConcurrentHashMap<>();

    @Override
    public void add(Class<?> instanceClass, Object instance){
        this.instances.putIfAbsent(instanceClass, instance);

        findAnnotationsInClass(instanceClass).forEach((annotationInClass) -> {
            incrementMapList(annotatedWithIndex, annotationInClass.getClass(), instance, LinkedList::new);
        });
        findInterfacesInClass(instanceClass).forEach(interfaceInClass -> {
            incrementMapList(implementsInterfaceIndex, interfaceInClass, instance, LinkedList::new);
        });
    }

    @Override
    public Object get(Class<?> instance){
        return this.instances.get(instance);
    }

    @Override
    public boolean contains(Class<?> classToGet){
        return this.instances.get(classToGet) != null;
    }

    @Override
    public <T> List<T> queryByImplementsInterface(Class<T> interfaceToCheck) {
        return implementsInterfaceIndex.containsKey(interfaceToCheck) ?
                new LinkedList<T>((Collection<? extends T>) implementsInterfaceIndex.get(interfaceToCheck)) :
                Collections.EMPTY_LIST;
    }

    @Override
    public List<Object> queryByAnnotatedWith(Class<? extends Annotation> annotationToCheck) {
        return annotatedWithIndex.containsKey(annotationToCheck) ?
                new LinkedList<>(annotatedWithIndex.get(annotationToCheck)) :
                Collections.EMPTY_LIST;
    }

    @Override
    public <T> List<T> queryByAnnotatedWithAndImplementsInterface(Class<? extends Annotation> annotationToCheck, Class<T> interfaceToCheck) {
        return null;
    }
}