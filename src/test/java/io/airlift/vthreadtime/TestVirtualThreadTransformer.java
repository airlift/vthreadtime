/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.vthreadtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.classfile.ClassFile.ACC_PRIVATE;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.constant.ConstantDescs.MTD_void;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestVirtualThreadTransformer
{
    private static final String VIRTUAL_THREAD = "java/lang/VirtualThread";
    private static final String HOOKS = "io/airlift/vthreadtime/hook/VirtualThreadHooks";
    private static final ClassFile CLASS_FILE = ClassFile.of();

    @Test
    void testJdkShapeTheTransformerReliesOn()
            throws Exception
    {
        ClassModel original = CLASS_FILE.parse(virtualThreadClass());
        assertThat(count(code(original, "mount", "()V"), ReturnInstruction.class)).isEqualTo(1);
        assertThat(count(code(original, "unmount", "()V"), ReturnInstruction.class)).isEqualTo(1);

        Method threadLocals = Thread.class.getDeclaredMethod("threadLocals");
        assertThat(Modifier.isStatic(threadLocals.getModifiers())).isFalse();
        assertThat(threadLocals.getReturnType().getName()).isEqualTo("java.lang.ThreadLocal$ThreadLocalMap");
    }

    @Test
    void testMountHookBeforeEveryReturn()
            throws Exception
    {
        ClassModel transformed = transformed();
        List<CodeElement> elements = instructions(code(transformed, "mount", "()V"));
        int returns = 0;
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) instanceof ReturnInstruction) {
                returns++;
                assertHookBefore(elements, i, "mounted");
            }
        }
        assertThat(returns).isEqualTo(1);
        assertThat(hookCalls(elements, "mounted")).isEqualTo(returns);
    }

    @Test
    void testUnmountHookAtEntry()
            throws Exception
    {
        ClassModel transformed = transformed();
        List<CodeElement> elements = instructions(code(transformed, "unmount", "()V"));
        int start = 0;
        while (elements.get(start) instanceof LabelTarget) {
            start++;
        }
        Set<Label> boundAfterHook = new HashSet<>();
        int next = start + 4;
        while (elements.get(next) instanceof LabelTarget target) {
            boundAfterHook.add(target.label());
            next++;
        }
        assertHookCall(elements.subList(start, start + 4), "unmounted", boundAfterHook);
        assertThat(hookCalls(elements, "unmounted")).isEqualTo(1);
    }

    @Test
    void testEverythingElseIsPreserved()
            throws Exception
    {
        ClassModel original = CLASS_FILE.parse(virtualThreadClass());
        ClassModel transformed = transformed();

        assertThat(transformed.thisClass().asInternalName()).isEqualTo(VIRTUAL_THREAD);
        assertThat(transformed.superclass().map(ClassEntry::asInternalName)).isEqualTo(original.superclass().map(ClassEntry::asInternalName));
        assertThat(transformed.fields().stream().map(field -> field.fieldName().stringValue()).toList())
                .isEqualTo(original.fields().stream().map(field -> field.fieldName().stringValue()).toList());
        assertThat(signatures(transformed)).isEqualTo(signatures(original));

        for (MethodModel method : original.methods()) {
            String signature = signature(method);
            MethodModel counterpart = method(transformed, method.methodName().stringValue(), method.methodType().stringValue());
            assertThat(annotations(counterpart)).as(signature).isEqualTo(annotations(method));
            if (isHooked(method)) {
                continue;
            }
            // Native methods have no Code attribute; compare presence, then bytes
            Optional<CodeAttribute> originalCode = method.findAttribute(Attributes.code());
            Optional<CodeAttribute> transformedCode = counterpart.findAttribute(Attributes.code());
            assertThat(transformedCode.isPresent()).as(signature).isEqualTo(originalCode.isPresent());
            if (originalCode.isPresent()) {
                assertThat(transformedCode.orElseThrow().codeArray()).as(signature).isEqualTo(originalCode.orElseThrow().codeArray());
            }
        }
    }

    @Test
    void testTransformedClassVerifies()
            throws Exception
    {
        byte[] original = virtualThreadClass();
        assertThat(CLASS_FILE.verify(original)).isEmpty();
        assertThat(CLASS_FILE.verify(VirtualThreadTransformer.transform(original))).isEmpty();
    }

    @Test
    void testRejectsOtherClass()
            throws Exception
    {
        byte[] other;
        try (InputStream input = TestVirtualThreadTransformer.class.getResourceAsStream("TestVirtualThreadTransformer.class")) {
            other = input.readAllBytes();
        }
        assertThatThrownBy(() -> VirtualThreadTransformer.transform(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected class java/lang/VirtualThread but found io/airlift/vthreadtime/TestVirtualThreadTransformer");
    }

    @Test
    void testRejectsMissingMethod()
    {
        byte[] withoutUnmount = CLASS_FILE.build(ClassDesc.ofInternalName(VIRTUAL_THREAD), classBuilder -> classBuilder
                .withMethodBody("mount", MTD_void, ACC_PRIVATE, CodeBuilder::return_));
        assertThatThrownBy(() -> VirtualThreadTransformer.transform(withoutUnmount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("java/lang/VirtualThread has no method unmount()V");
    }

    @Test
    void testRejectsStaticMethod()
    {
        byte[] staticMount = CLASS_FILE.build(ClassDesc.ofInternalName(VIRTUAL_THREAD), classBuilder -> classBuilder
                .withMethodBody("mount", MTD_void, ACC_PRIVATE | ACC_STATIC, CodeBuilder::return_));
        assertThatThrownBy(() -> VirtualThreadTransformer.transform(staticMount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("java/lang/VirtualThread.mount()V is static");
    }

    @Test
    void testAcceptsThreadLocalsAccessorOfRunningJdk()
    {
        VirtualThreadTransformer.checkThreadLocalsAccessor(Thread.class);
    }

    @Test
    void testRejectsMissingThreadLocalsAccessor()
    {
        assertThatThrownBy(() -> VirtualThreadTransformer.checkThreadLocalsAccessor(WithoutAccessor.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(WithoutAccessor.class.getName() + " has no method threadLocals()")
                .hasCauseInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void testRejectsStaticThreadLocalsAccessor()
    {
        assertThatThrownBy(() -> VirtualThreadTransformer.checkThreadLocalsAccessor(StaticAccessor.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(StaticAccessor.class.getName() + ".threadLocals() is static");
    }

    @Test
    void testRejectsThreadLocalsAccessorOfOtherType()
    {
        assertThatThrownBy(() -> VirtualThreadTransformer.checkThreadLocalsAccessor(ObjectAccessor.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ObjectAccessor.class.getName() + ".threadLocals() returns java.lang.Object, not java.lang.ThreadLocal$ThreadLocalMap");
    }

    private static byte[] virtualThreadClass()
            throws IOException
    {
        try (InputStream input = Object.class.getResourceAsStream("/java/lang/VirtualThread.class")) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }

    private static ClassModel transformed()
            throws IOException
    {
        return CLASS_FILE.parse(VirtualThreadTransformer.transform(virtualThreadClass()));
    }

    private static boolean isHooked(MethodModel method)
    {
        String signature = signature(method);
        return signature.equals("mount()V") || signature.equals("unmount()V");
    }

    private static List<String> signatures(ClassModel model)
    {
        return model.methods().stream().map(TestVirtualThreadTransformer::signature).toList();
    }

    private static String signature(MethodModel method)
    {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    private static Optional<List<String>> annotations(MethodModel method)
    {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(RuntimeVisibleAnnotationsAttribute::annotations)
                .map(annotations -> annotations.stream().map(Annotation::className).map(Utf8Entry::stringValue).toList());
    }

    private static MethodModel method(ClassModel model, String name, String type)
    {
        return model.methods().stream()
                .filter(method -> method.methodName().equalsString(name) && method.methodType().equalsString(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No method " + name + type));
    }

    private static CodeModel code(ClassModel model, String name, String type)
    {
        return method(model, name, type).code().orElseThrow();
    }

    /// Instructions and label bindings in order, without line numbers and local variable debug entries.
    private static List<CodeElement> instructions(CodeModel code)
    {
        return code.elementList().stream()
                .filter(element -> element instanceof Instruction || element instanceof LabelTarget)
                .toList();
    }

    private static long count(CodeModel code, Class<? extends Instruction> type)
    {
        return code.elementList().stream().filter(type::isInstance).count();
    }

    private static long hookCalls(List<CodeElement> elements, String hook)
    {
        return elements.stream()
                .filter(element -> element instanceof InvokeInstruction invoke &&
                        invoke.owner().asInternalName().equals(HOOKS) &&
                        invoke.name().equalsString(hook))
                .count();
    }

    /// Asserts the four hook instructions directly precede the exit at `exitIndex`, with the
    /// `ifnull` skipping to a label bound between the hook call and the exit.
    private static void assertHookBefore(List<CodeElement> elements, int exitIndex, String hook)
    {
        int index = exitIndex;
        Set<Label> boundBeforeExit = new HashSet<>();
        while (elements.get(index - 1) instanceof LabelTarget target) {
            boundBeforeExit.add(target.label());
            index--;
        }
        assertHookCall(elements.subList(index - 4, index), hook, boundBeforeExit);
    }

    private static final class WithoutAccessor {}

    @SuppressWarnings("UnusedMethod")
    private static final class StaticAccessor
    {
        static Object threadLocals()
        {
            return new Object();
        }
    }

    @SuppressWarnings("UnusedMethod")
    private static final class ObjectAccessor
    {
        Object threadLocals()
        {
            return this;
        }
    }

    private static void assertHookCall(List<CodeElement> hookElements, String hook, Set<Label> skipTargets)
    {
        assertThat(hookElements.get(0)).isInstanceOfSatisfying(LoadInstruction.class, load -> {
            assertThat(load.typeKind()).isEqualTo(TypeKind.REFERENCE);
            assertThat(load.slot()).isEqualTo(0);
        });
        assertThat(hookElements.get(1)).isInstanceOfSatisfying(InvokeInstruction.class, invoke -> {
            assertThat(invoke.opcode()).isEqualTo(Opcode.INVOKEVIRTUAL);
            assertThat(invoke.owner().asInternalName()).isEqualTo("java/lang/Thread");
            assertThat(invoke.name().stringValue()).isEqualTo("threadLocals");
            assertThat(invoke.type().stringValue()).isEqualTo("()Ljava/lang/ThreadLocal$ThreadLocalMap;");
        });
        assertThat(hookElements.get(2)).isInstanceOfSatisfying(BranchInstruction.class, branch -> {
            assertThat(branch.opcode()).isEqualTo(Opcode.IFNULL);
            assertThat(skipTargets).contains(branch.target());
        });
        assertThat(hookElements.get(3)).isInstanceOfSatisfying(InvokeInstruction.class, invoke -> {
            assertThat(invoke.opcode()).isEqualTo(Opcode.INVOKESTATIC);
            assertThat(invoke.owner().asInternalName()).isEqualTo(HOOKS);
            assertThat(invoke.name().stringValue()).isEqualTo(hook);
            assertThat(invoke.type().stringValue()).isEqualTo("()V");
        });
    }
}
