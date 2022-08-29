package es.dependencyinjector;

import es.dependencyinjector.abstractions.AbstractionsSaver;
import es.dependencyinjector.abstractions.AbstractionsScanner;
import es.dependencyinjector.exceptions.UnknownDependency;
import es.dependencyinjector.providers.ProvidersScanner;
import es.dependencyinjector.abstractions.AbstractionsRepository;
import es.dependencyinjector.repository.DependenciesRepository;
import es.dependencyinjector.providers.DependencyProvider;
import es.dependencyinjector.providers.ProvidersRepository;
import lombok.SneakyThrows;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static es.dependencyinjector.utils.ReflectionUtils.*;
import static es.dependencyinjector.utils.Utils.*;

public final class DependencyInjectorScanner {
    private final DependenciesRepository dependencies;
    private final AbstractionsRepository abstractionsRepository;
    private final ProvidersRepository providersRepository;
    private final DependencyInjectorConfiguration configuration;
    private final Reflections reflections;
    private final ExecutorService executor;
    private final ProvidersScanner providersScanner;
    private final AbstractionsSaver abstractionsSaver;
    private final AbstractionsScanner abstractionsScanner;

    public DependencyInjectorScanner(DependencyInjectorConfiguration configuration) {
        this.configuration = configuration;
        this.dependencies = configuration.getDependenciesRepository();
        this.providersRepository = configuration.getProvidersRepository();
        this.abstractionsRepository = configuration.getAbstractionsRepository();
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        this.reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(configuration.getPackageToScan()))
                .setScanners(new TypeAnnotationsScanner(),
                        new SubTypesScanner(), new MethodAnnotationsScanner()));
        this.providersScanner = new ProvidersScanner(this.reflections, this.configuration);
        this.abstractionsSaver = new AbstractionsSaver(this.configuration);
        this.abstractionsScanner = new AbstractionsScanner(this.configuration, this.reflections);
    }

    public void start() {
        runCheckedOrTerminate(() -> {
            this.searchForProviders();
            this.searchForAbstractions();
            this.searchForClassesToInstantiate();
        });
    }

    private void searchForProviders() {
        this.providersScanner.scan().forEach(this.providersRepository::save);
    }

    private void searchForAbstractions() {
        this.abstractionsScanner.scan().forEach(this.abstractionsSaver::save);
    }

    @SneakyThrows
    private void searchForClassesToInstantiate() {
        Set<Class<?>> classesAnnotated = this.getClassesAnnotated();
        CountDownLatch countDownLatch = new CountDownLatch(classesAnnotated.size());

        for (Class<?> classAnnotatedWith : classesAnnotated){
            this.executor.execute(() -> runCheckedOrTerminate(() -> {
                instantiateClass(classAnnotatedWith);
                countDownLatch.countDown();
            }));
        }

        countDownLatch.await();
        this.executor.shutdown();
    }

    private Object instantiateClass(Class<?> classAnnotatedWith) throws Exception {
        Optional<Constructor<?>> constructorOptional = getSmallestConstructor(classAnnotatedWith);
        boolean alreadyInstanced = this.dependencies.contains(classAnnotatedWith);
        boolean doestHaveEmptyConstructor = constructorOptional.isPresent();

        if (doestHaveEmptyConstructor && !alreadyInstanced) {
            Constructor<?> constructor = constructorOptional.get();
            this.ensureAllParametersAreFound(constructor.getParameterTypes());
            Class<?>[] parametersOfConstructor = constructor.getParameterTypes();
            Object[] instances = new Object[parametersOfConstructor.length];

            for (int i = 0; i < parametersOfConstructor.length; i++) {
                Class<?> parameterOfConstructor = parametersOfConstructor[i];
                boolean isAbstraction = isAbstraction(parameterOfConstructor);

                instances[i] = instantiateClass(isAbstraction ?
                        this.getImplementationFromAbstraction(parameterOfConstructor) :
                        parameterOfConstructor
                );
            }

            Object newInstance = constructor.newInstance(instances);
            saveDependency(newInstance);

            return newInstance;
        }else {
            return alreadyInstanced ?
                    this.dependencies.get(classAnnotatedWith) :
                    createInstanceAndSave(classAnnotatedWith);
        }
    }

    private void saveDependency(Object newInstance) {
        Class<?> instanceClass = newInstance.getClass();
        boolean isImplementation = isImplementation(instanceClass);

        List<Class<?>> abstractions = getAbstractions(instanceClass);
        for (Class<?> abstraction : abstractions)
            this.dependencies.add(isImplementation ? abstraction : instanceClass, newInstance);

        this.providersRepository.findByProviderClass(instanceClass).ifPresent(dependencyProviders -> {
            for (DependencyProvider provider : dependencyProviders) {
                runCheckedOrTerminate(() -> {
                    Object instanceProvided = provider.getProviderMethod().invoke(newInstance);
                    this.dependencies.add(instanceProvided.getClass(), instanceProvided);
                });
            }
        });
    }

    private Object createInstanceAndSave(Class<?> classAnnotatedWith) throws Exception {
        Object newInstance = classAnnotatedWith.newInstance();
        this.saveDependency(newInstance);

        return newInstance;
    }

    private void ensureAllParametersAreFound(Class<?>[] parameterTypes) throws UnknownDependency {
        for (Class<?> parameterType : parameterTypes) {
            boolean notAnnotated = !isAnnotatedWith(parameterType, this.configuration.getAnnotations());
            boolean isAbstraction = isAbstraction(parameterType);
            boolean implementationNotFound = !this.abstractionsRepository.contains(parameterType);
            boolean notProvided = !this.providersRepository.findByDependencyClassProvided(parameterType).isPresent();

            if(isAbstraction && implementationNotFound)
                throw new UnknownDependency("Implementation not found for %s, it may not be annotated", parameterType.getName());
            if((!isAbstraction && notAnnotated) && notProvided)
                throw new UnknownDependency("Unknown dependency type %s. Make sure it is annotated", parameterType.getName());
        }
    }

    private Set<Class<?>> getClassesAnnotated() {
        return this.configuration.getAnnotations().stream()
                .map(this.reflections::getTypesAnnotatedWith)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    private Class<?> getImplementationFromAbstraction(Class<?> abstraction) throws Exception {
        boolean alreadyDeclaredInConfig = this.configuration.getAbstractions().containsKey(abstraction);

        return alreadyDeclaredInConfig ?
                this.configuration.getAbstractions().get(abstraction) :
                this.abstractionsRepository.get(abstraction);
    }
}
