/**
 * Copyright 2014 John Ericksen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.silver;

import com.sun.codemodel.*;
import org.androidtransfuse.adapter.*;
import org.androidtransfuse.adapter.element.ASTElementFactory;
import org.androidtransfuse.gen.ClassGenerationUtil;
import org.androidtransfuse.gen.ClassNamer;
import org.androidtransfuse.gen.UniqueVariableNamer;
import org.androidtransfuse.transaction.AbstractCompletionTransactionWorker;
import org.androidtransfuse.util.matcher.Matcher;

import javax.annotation.processing.RoundEnvironment;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.*;

/**
 * @author John Ericksen
 */
public class SilverWorker extends AbstractCompletionTransactionWorker<Provider<ASTType>, JDefinedClass> {

    private final Provider<RoundEnvironment> roundEnvironmentProvider;
    private final ASTElementFactory astElementFactory;
    private final ClassGenerationUtil generationUtil;
    private final UniqueVariableNamer namer;

    @Inject
    public SilverWorker(Provider<RoundEnvironment> roundEnvironmentProvider, ASTElementFactory astElementFactory, ClassGenerationUtil generationUtil, UniqueVariableNamer namer) {
        this.roundEnvironmentProvider = roundEnvironmentProvider;
        this.astElementFactory = astElementFactory;
        this.generationUtil = generationUtil;
        this.namer = namer;
    }

    @Override
    public JDefinedClass innerRun(Provider<ASTType> astTypeProvider) {
        ASTType implementation = astTypeProvider.get();

        try {
            JDefinedClass silverimpl = generationUtil.defineClass(ClassNamer.className(implementation).append(SilverUtil.IMPL_EXT).build());

            silverimpl._implements(generationUtil.ref(implementation));


            // Builds the following static method:

            // private static Set<Class> buildSet(Class... input) {
            //     Set<java.lang.Class> set = new HashSet<Class>();
            //     Collections.addAll(set, input);
            //     return Collections.unmodifiableSet(set);
            // }

            JClass setRef = generationUtil.ref(Set.class).narrow(Class.class);
            JClass hashsetRef = generationUtil.ref(HashSet.class).narrow(Class.class);

            JMethod buildSet = silverimpl.method(JMod.PRIVATE | JMod.STATIC, setRef, "buildSet");
            JVar inputVar = buildSet.varParam(Class.class, namer.generateName(Class.class));
            JBlock buildSetBody = buildSet.body();

            JVar setVar = buildSetBody.decl(setRef, namer.generateName(setRef), JExpr._new(hashsetRef));
            buildSetBody.staticInvoke(generationUtil.ref(Collections.class), "addAll").arg(setVar).arg(inputVar);
            buildSetBody._return(generationUtil.ref(Collections.class).staticInvoke("unmodifiableSet").arg(setVar));

            for (ASTMethod astMethod : implementation.getMethods()) {

                JClass collectionType = generationUtil.narrowRef(astMethod.getReturnType());

                JFieldVar collectionField = silverimpl.field(JMod.PRIVATE | JMod.FINAL | JMod.STATIC, collectionType.erasure(), namer.generateName(collectionType), generateTypeCollection(astMethod));

                JMethod method = silverimpl.method(JMod.PUBLIC, collectionType, astMethod.getName());

                method.body()._return(collectionField);
            }

            return silverimpl;

        } catch (JClassAlreadyExistsException e) {
            throw new SilverRuntimeException("Class Already exists", e);
        }
    }

    private JExpression generateTypeCollection(ASTMethod method) {

        final List<Matcher<ASTType>> matcherConjunction = new ArrayList<Matcher<ASTType>>();

        if(method.isAnnotated(AnnotatedBy.class)){
            final ASTType annotatedBy = method.getASTAnnotation(AnnotatedBy.class).getProperty("value", ASTType.class);

            matcherConjunction.add(new Matcher<ASTType>() {
                @Override
                public boolean matches(ASTType astType) {
                    final ArrayList<ASTAnnotation> annotations = new ArrayList<ASTAnnotation>();

                    for( ASTMethod astMethod : astType.getMethods()) {
                        annotations.addAll(astMethod.getAnnotations());

                        for( ASTParameter astParameter : astMethod.getParameters()) {
                            annotations.addAll(astParameter.getAnnotations());
                        }
                    }

                    for( ASTField astField : astType.getFields()) {
                        annotations.addAll(astField.getAnnotations());
                    }

                    annotations.addAll(astType.getAnnotations());

                    for (ASTAnnotation astAnnotation : annotations) {
                        if(astAnnotation.getASTType().equals(annotatedBy)){
                            return true;
                        }
                    }
                    return false;
                }
            });
        }
        if(method.isAnnotated(Inherits.class)){
            final ASTType extendsType = method.getASTAnnotation(Inherits.class).getProperty("value", ASTType.class);

            matcherConjunction.add(new Matcher<ASTType>(){

                @Override
                public boolean matches(ASTType input) {
                    return input.inheritsFrom(extendsType) && !input.equals(extendsType);
                }
            });
        }


        Matcher<ASTType> matcher = new Matcher<ASTType>() {
            @Override
            public boolean matches(ASTType input) {
                if(matcherConjunction.size() == 0){
                    return false;
                }

                for (Matcher<ASTType> matcher : matcherConjunction) {
                    if(!matcher.matches(input)){
                        return false;
                    }
                }
                return true;
            }
        };

        List<ASTType> matched = new ArrayList<ASTType>();
        for (Element element : roundEnvironmentProvider.get().getRootElements()) {
            ASTType elementType = astElementFactory.getType((TypeElement) element);
            if(matcher.matches(elementType)){
                matched.add(elementType);
            }
        }


        JInvocation buildSetInvocation = JExpr.invoke("buildSet");

        for (ASTType astType : matched) {
            buildSetInvocation.arg(generationUtil.ref(astType).staticRef("class"));
        }

        return buildSetInvocation;
    }
}
