package com.microsoft.azure.toolkit.lib.legacy.function.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.reflections.Reflections;

import com.microsoft.azure.toolkit.lib.appservice.function.core.AzureFunctionsAnnotationConstants;

public class IndexUtils {
    /**
     * Adds required annotations to the index. As long as the annotations artifacts do not ship with
     * pre-built indexes with the jandex-maven-plugin, we have to add them manually. When this changes,
     * this class (and "reflections" dependency) should be moved to tests only. We (currently) sill
     * need it there because we build a custom classpath for testing, so we do not get to enjoy the
     * pre-built index indexes.
     */
    public static IndexView enrichIndex(IndexView originalIndex) {
        try {
            Indexer indexer = new Indexer();
            Reflections reflections = new Reflections(
                    DotName.createSimple(AzureFunctionsAnnotationConstants.FUNCTION_NAME).packagePrefix());
            for (Class<?> annotation : reflections.getSubTypesOf(Annotation.class)) {
                indexer.indexClass(annotation);
            }
            indexer.indexClass(Target.class);
            indexer.indexClass(Retention.class);
            return CompositeIndex.create(originalIndex, indexer.complete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
