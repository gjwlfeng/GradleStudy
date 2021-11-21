/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.dsl.decorator

import com.android.build.gradle.internal.dsl.AgpDslLockedException
import com.android.build.gradle.internal.dsl.Lockable
import com.android.build.gradle.internal.dsl.decorator.annotation.NonNullableSetter
import com.android.build.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.android.build.gradle.internal.fixtures.FakeObjectFactory
import com.android.build.gradle.internal.services.DslServices
import com.android.testutils.MockitoKt.any
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import groovy.util.Eval
import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.lang.reflect.Modifier
import javax.inject.Inject
import kotlin.test.assertFailsWith
import kotlin.test.fail

/** Unit tests for the code generated by the AgpDslDecorator */
class DslDecoratorUnitTest {

    interface Empty

    @Test
    fun `check generated constructor `() {
        val decorator = DslDecorator(listOf())

        val decorated = decorator.decorate(Empty::class)
        val constructor = decorated.getConstructor(DslServices::class.java)

        assertWithMessage("Expected to have @Injected on constructor")
            .that(constructor.declaredAnnotations.map { it.annotationClass })
            .containsExactly(Inject::class)

        // Check does not throw
        constructor.newInstance(dslServices)
    }

    abstract class EmptyWithInjectAnnotation @Inject constructor()

    abstract class WithPrimitiveInConstructor @Inject constructor(val integer: Int, val boxed: Int?, val dslServices: DslServices)

    @Test
    fun `check constructors with arguments work correctly`() {
        val decorator = DslDecorator(listOf())

        val decorated = decorator.decorate(WithPrimitiveInConstructor::class)
        val o = decorated.getDeclaredConstructor(Int::class.java, Integer::class.java, DslServices::class.java).newInstance(1, 2, dslServices)

        assertWithMessage("Constructor values should be passed through")
            .that(o.integer).isEqualTo(1)
        assertWithMessage("Constructor values should be passed through")
            .that(o.boxed).isEqualTo(2)
        assertWithMessage("Constructor values should be passed through")
            .that(o.dslServices).isSameInstanceAs(dslServices)
    }


    @Suppress("unused")
    abstract class ConstructorWithArgsWithoutInject constructor(val integer: Int, val dslServices: DslServices?) {
        constructor(): this(0, null)
        @Inject constructor(string: String, dslServices: DslServices): this(Integer.getInteger(string)!!, dslServices)
    }

    @Test
    fun `check only annotated and zero-arg constructors are generated`() {
        val decorator = DslDecorator(listOf())

        val decorated = decorator.decorate(ConstructorWithArgsWithoutInject::class)
        val basedOnZeroArgumentConstructor = decorated.getDeclaredConstructor(DslServices::class.java)
        assertWithMessage("Expected to have @Injected added")
            .that(basedOnZeroArgumentConstructor.declaredAnnotations.map { it.annotationClass })
            .containsExactly(Inject::class)

        val injectAnnotatedConstructor = decorated.getDeclaredConstructor(String::class.java, DslServices::class.java)
        assertWithMessage("Expected to be copied to generated constructor")
            .that(injectAnnotatedConstructor.declaredAnnotations.map { it.annotationClass })
            .containsExactly(Inject::class)


        fun Class<*>.getIntConstructor() = getDeclaredConstructor(Int::class.java, DslServices::class.java)
        // Given a constructor that is not annotated on the superclass
        assertThat(decorated.superclass.getIntConstructor().declaredAnnotations).isEmpty()
        // No constructor should be generated
        assertFailsWith(NoSuchMethodException::class) {
            decorated.getIntConstructor()
        }
    }

    interface WithManagedString {
        var managedString: String
    }

    @Test
    fun `check managed var properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.String))

        val decorated = decorator.decorate(WithManagedString::class.java)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)

        assertThat(o).isNotNull()
        assertThat(o.managedString).isNull()
        o.managedString = "a"
        assertThat(o.managedString).isEqualTo("a")

    }

    @Test
    fun `check locking works for managed var properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.String))
        val decorated = decorator.decorate(WithManagedString::class)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
         o.managedString = "a"
        (o as Lockable).lock()
        val failure = assertFailsWith<AgpDslLockedException> {
            o.managedString = "b"
        }
        assertThat(failure).hasMessageThat().contains("It is too late to set managedString")
        assertThat(o.managedString).isEqualTo("a")
    }

    interface WithManagedBoolean {
        var managedBoolean: Boolean
    }

    @Test
    fun `check managed boolean properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.Boolean))
        val decorated = decorator.decorate(WithManagedBoolean::class.java)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)

        assertThat(o.managedBoolean).isFalse()
        o.managedBoolean = true
        assertThat(o.managedBoolean).isTrue()
    }


    interface WithManagedNullableBoolean {
        var managedBoolean: Boolean?
    }

    @Test
    fun `check managed nullable boolean properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.NullableBoolean))
        val decorated = decorator.decorate(WithManagedNullableBoolean::class.java)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)

        assertThat(o.managedBoolean).isNull()
        o.managedBoolean = true
        assertThat(o.managedBoolean).isTrue()
        o.managedBoolean = false
        assertThat(o.managedBoolean).isFalse()
    }

    interface WithManagedInteger {
        var managedInteger: Int
    }

    @Test
    fun `check managed integer properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.Int))
        val decorated = decorator.decorate(WithManagedInteger::class.java)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)

        assertThat(o.managedInteger).isEqualTo(0)
        o.managedInteger = -3
        assertThat(o.managedInteger).isEqualTo(-3)

        (o as Lockable).lock()
        val exception = assertFailsWith(AgpDslLockedException::class) {
            o.managedInteger = 6
        }
        assertThat(exception).hasMessageThat().isEqualTo(
            """
                It is too late to set managedInteger
                It has already been read to configure this project.
                Consider either moving this call to be during evaluation,
                or using the variant API.""".trimIndent()
        )
        assertThat(o.managedInteger).isEqualTo(-3)
    }

    interface WithManagedNullableInteger {
        var managedNullableInteger: Int?
    }

    @Test
    fun `check managed nullable integer properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.NullableInt))
        val decorated = decorator.decorate(WithManagedNullableInteger::class.java)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)

        assertThat(o.managedNullableInteger).isEqualTo(null)
        o.managedNullableInteger = 1
        assertThat(o.managedNullableInteger).isEqualTo(1)
        o.managedNullableInteger = null
        assertThat(o.managedNullableInteger).isEqualTo(null)
        o.managedNullableInteger = -3
        assertThat(o.managedNullableInteger).isEqualTo(-3)

        (o as Lockable).lock()
        val exception = assertFailsWith(AgpDslLockedException::class) {
            o.managedNullableInteger = 6
        }
        assertThat(exception).hasMessageThat().isEqualTo(
            """
                It is too late to set managedNullableInteger
                It has already been read to configure this project.
                Consider either moving this call to be during evaluation,
                or using the variant API.""".trimIndent()
        )
        assertThat(o.managedNullableInteger).isEqualTo(-3)
    }

    abstract class WithExtraSetter {
        abstract var managedString: String
        fun setManagedString(value: Int) {
            managedString = "$value"
        }
    }

    @Test
    fun `check extra setters`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.String))
        val decorated = decorator.decorate(WithExtraSetter::class.java)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(o).isNotNull()
        assertThat(o.managedString).isNull()
        o.managedString = "a"
        assertThat(o.managedString).isEqualTo("a")
        o.setManagedString(4)
        assertThat(o.managedString).isEqualTo("4")
    }

    abstract class LeaveAbstract {
        @Suppress("unused")
        abstract val forGradle: Property<String>
    }

    @Test
    fun `check unknown types are left as abstract`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.String))
        val decorated = decorator.decorate(LeaveAbstract::class)
        val method = decorated.getMethod("getForGradle")
        assertThat(Modifier.toString(method.modifiers)).isEqualTo("public abstract")
    }


    abstract class WithProtectedField {
        protected abstract var compileSdkVersion: String?

        var compileSdk: String?
            get() = compileSdkVersion
            set(value) { compileSdkVersion = value }
    }

    @Test
    fun `check protected field is implemented`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Var.String))
            .decorate(WithProtectedField::class)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(o).isNotNull()
        assertThat(o.compileSdk).isNull()
        o.compileSdk = "a"
        assertThat(o.compileSdk).isEqualTo("a")
        val method = decorated.getDeclaredMethod("getCompileSdkVersion")
        assertThat(Modifier.toString(method.modifiers)).isEqualTo("protected")
    }

    abstract class Subclass: WithProtectedField()

    @Test
    fun `check protected field in superclass is implemented`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Var.String))
            .decorate(Subclass::class)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(o).isNotNull()
        assertThat(o.compileSdk).isNull()
        o.compileSdk = "a"
        assertThat(o.compileSdk).isEqualTo("a")
        val method = decorated.getDeclaredMethod("getCompileSdkVersion")
        assertThat(Modifier.toString(method.modifiers)).isEqualTo("protected")
    }

    abstract class PublicFieldOverridesProtectedField: WithProtectedField() {
        public abstract override var compileSdkVersion: String?
    }

    @Test
    fun `check handling of public override of a protected field`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Var.String))
            .decorate(PublicFieldOverridesProtectedField::class)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(o).isNotNull()
        assertThat(o.compileSdkVersion).isNull()
        o.compileSdkVersion = "a"
        assertThat(o.compileSdkVersion).isEqualTo("a")
        val method = decorated.getDeclaredMethod("getCompileSdkVersion")
        assertThat(Modifier.toString(method.modifiers)).isEqualTo("public")
    }

    interface LockableWithList : Lockable {
        val foo: MutableList<String>
    }

    @Test
    fun `check locking propagation`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Collection.List))
            .decorate(LockableWithList::class)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        o.foo += "one"
        o.lock()
        assertThat(o.foo).containsExactly("one")
        val exception = assertFailsWith(AgpDslLockedException::class) {
            o.foo += "two"
        }
        assertThat(exception).hasMessageThat().isEqualTo(
            """
                It is too late to modify foo
                It has already been read to configure this project.
                Consider either moving this call to be during evaluation,
                or using the variant API.""".trimIndent()
        )
        assertThat(o.foo).containsExactly("one")
    }

    interface A {
        val foo: MutableList<String>
    }

    abstract class B {
        abstract val foo: MutableCollection<String>
    }

    abstract class C: B(), A

    @Test
    fun `check synthetic methods where interface has more specific type`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Collection.List)).decorate(C::class)
        val c = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        val asA: A = c
        asA.foo.add("a")
        assertThat(c.foo).containsExactly("a")
        val asB: B = c
        asB.foo.add("b")
        assertThat(c.foo).containsExactly("a", "b")
    }

    interface X {
        val foo: MutableCollection<String>
    }

    abstract class Y {
        abstract val foo: MutableList<String>
    }

    abstract class Z: Y(), X

    @Test
    fun `check synthetic methods where supertype has more specific type`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Collection.List)).decorate(Z::class)
        val z = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        val asX: X = z
        asX.foo.add("a")
        assertThat(asX.foo).containsExactly("a")
        val asY: Y = z
        asY.foo.add("b")
        assertThat(asY.foo).containsExactly("a", "b")
    }

    interface InterfaceWithVar {
        var foo: String
    }

    abstract class WithoutConcreteImplementation: InterfaceWithVar {
        abstract override var foo: String
    }

    abstract class ConcreteImplementation : WithoutConcreteImplementation() {
        override var foo: String = "hello"
    }

    @Test
    fun `check doesn't override concrete implementations`() {
        val dslDecorator = DslDecorator(listOf(SupportedPropertyType.Var.String))
        val decoratedInterface = dslDecorator.decorate(WithoutConcreteImplementation::class)
            .getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(decoratedInterface.foo).isNull()
        val decorated = dslDecorator.decorate(ConcreteImplementation::class)
            .getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(decorated.foo).isEqualTo("hello")
    }

    interface WithList {
        val list: MutableList<String>
    }

    @Test
    fun `check groovy setter generation`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Collection.List))
            .decorate(WithList::class)
        val withList = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        Eval.me("withList", withList, "withList.list += ['one', 'two']")
        assertThat(withList.list).containsExactly("one", "two").inOrder()
        Eval.me("withList", withList, "withList.list += 'three'")
        assertThat(withList.list).containsExactly("one", "two", "three").inOrder()
        // Check self-assignment preserves values
        Eval.me("withList", withList, "withList.list = withList.list")
        assertThat(withList.list).containsExactly("one", "two", "three").inOrder()
    }

    interface WithSet {
        val set: MutableSet<String>
    }

    @Test
    fun `check groovy setter generation for set`() {
        val decorated = DslDecorator(listOf(SupportedPropertyType.Collection.Set))
            .decorate(WithSet::class)
        val withSet = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        assertThat(withSet.set::class.java).isEqualTo(LockableSet::class.java)
        Eval.me("withSet", withSet, "withSet.set += ['one', 'two']")
        assertThat(withSet.set).containsExactly("one", "two").inOrder()
        Eval.me("withSet", withSet, "withSet.set += 'three'")
        assertThat(withSet.set).containsExactly("one", "two", "three").inOrder()
        // Check self-assignment preserves values
        Eval.me("withSet", withSet, "withSet.set = withSet.set")
        assertThat(withSet.set).containsExactly("one", "two", "three").inOrder()
    }

    interface SubBlock {
        var string: String
    }

    interface WithSubBlock {
        val subBlock: SubBlock
        fun subBlock(action: SubBlock.() -> Unit)
    }

    /** The new sub blocks require no explicit implementation  */
    @Test
    fun `check green-field sub-block instantiation`() {
        val subBlockPropertyType = SupportedPropertyType.Block(SubBlock::class.java, SubBlock::class.java)
        val decorator = DslDecorator(listOf(subBlockPropertyType, SupportedPropertyType.Var.String))
        registerTestDecorator(decorator)

        val decorated = decorator.decorate(WithSubBlock::class)
        val withSubBlock: WithSubBlock = FakeObjectFactory.factory.newInstance(decorated, dslServices)

        withSubBlock.subBlock.string = "one"
        assertThat(withSubBlock.subBlock.string).isEqualTo("one")
        withSubBlock.subBlock {
            string = "two"
        }
        assertThat(withSubBlock.subBlock.string).isEqualTo("two")

        // Check action method (used by Gradle to generate the groovy closure method)
        val action = Action<SubBlock> { it.string = "three" }
        decorated.getDeclaredMethod("subBlock", Action::class.java)
            .invoke(withSubBlock, action)
        assertThat(withSubBlock.subBlock.string).isEqualTo("three")

        // Check Groovy use
        Eval.me("withSubBlock", withSubBlock, """
            withSubBlock.subBlock {
                string = "four"
            }
        """.trimIndent())

        assertThat(withSubBlock.subBlock.string).isEqualTo("four")
    }

    abstract class SubBlockImpl: SubBlock {
        fun implMethod(): Int { return 3 }
    }

    abstract class WithSubBlockImpl @Inject constructor(dslServices: DslServices): WithSubBlock {
        abstract override val subBlock: SubBlockImpl
    }

    @Test
    fun `check existing sub-block instantiation`() {
        val subBlockPropertyType = SupportedPropertyType.Block(
            type = SubBlock::class.java,
            implementationType = SubBlockImpl::class.java
        )
        val decorator = DslDecorator(listOf(subBlockPropertyType, SupportedPropertyType.Var.String))
        registerTestDecorator(decorator)

        val decorated = decorator.decorate(WithSubBlockImpl::class)
        val withSubBlockImpl: WithSubBlockImpl = FakeObjectFactory.factory.newInstance(decorated, dslServices)
        val withSubBlock: WithSubBlock = withSubBlockImpl

        // Check that we return the implementation type
        withSubBlockImpl.subBlock.implMethod()
        withSubBlockImpl.subBlock.string = "zero"
        assertThat(withSubBlock.subBlock.string).isEqualTo("zero")

        withSubBlock.subBlock.string = "one"
        assertThat(withSubBlock.subBlock.string).isEqualTo("one")
        withSubBlock.subBlock {
            string = "two"
        }
        assertThat(withSubBlock.subBlock.string).isEqualTo("two")
        Eval.me("withSubBlock", withSubBlock, """
            withSubBlock.subBlock {
                string = "three"
            }
        """.trimIndent())

        assertThat(withSubBlock.subBlock.string).isEqualTo("three")

    }

    @Test
    fun `check locking works for subBlocks`() {
        val subBlockPropertyType = SupportedPropertyType.Block(SubBlock::class.java, SubBlock::class.java)
        val decorator = DslDecorator(listOf(subBlockPropertyType, SupportedPropertyType.Var.String))
        registerTestDecorator(decorator)

        val decorated = decorator.decorate(WithSubBlock::class)
        val withSubBlock: WithSubBlock = FakeObjectFactory.factory.newInstance(decorated, dslServices)
        withSubBlock.subBlock.string= "a"
        (withSubBlock as Lockable).lock()
        val failure = assertFailsWith<AgpDslLockedException> {
            withSubBlock.subBlock.string = "b"
        }
        assertThat(failure).hasMessageThat().contains("It is too late to set string")
        assertThat(withSubBlock.subBlock.string).isEqualTo("a")
    }

    abstract class WithNonNullableValue
    @Inject @WithLazyInitialization(methodName = "lazyInit") constructor() {
        @set:NonNullableSetter
        abstract var value: String?

        protected fun lazyInit() {
            value = "default value"
        }
    }

    @Test
    fun `check non nullable setter annotation`() {
        val withNonNullableValue = DslDecorator(listOf(SupportedPropertyType.Var.String))
            .decorate(WithNonNullableValue::class)
            .getDeclaredConstructor(DslServices::class.java)
            .newInstance(dslServices)

        assertThat(withNonNullableValue.value).isEqualTo("default value")
        try {
            withNonNullableValue.value = null
            fail("Value shouldn't be settable to null")
        } catch (e: NullPointerException) { }
        assertThat(withNonNullableValue.value).isEqualTo("default value")
        withNonNullableValue.value = "new value"
        try {
            withNonNullableValue.value = null
            fail("Value shouldn't be settable to null")
        } catch (e: NullPointerException) { }
        assertThat(withNonNullableValue.value).isEqualTo("new value")
    }

    private val dslServices: DslServices = mock(DslServices::class.java)

    private fun registerTestDecorator(decorator: DslDecorator) {
        Mockito.`when`(
            dslServices.newDecoratedInstance(any(Class::class.java), any(DslServices::class.java))
        ).then { invocation ->
            val toDecorate = invocation.getArgument<Class<*>>(0)
            val dslServices = invocation.getArgument<DslServices>(1)
            val decorated = decorator.decorate(toDecorate)
            FakeObjectFactory.factory.newInstance(decorated, dslServices)
        }
    }

    abstract class WithCustomImpl() {

        protected abstract val _list: MutableList<String>

        // An example of a wrapping collection
        class SortingList(private val delegate: MutableList<String>): AbstractMutableList<String>(){
            override val size: Int = delegate.size
            override fun get(index: Int): String = delegate[index]
            override fun add(index: Int, element: String) = delegate.add(index, element).also { delegate.sort() }
            override fun removeAt(index: Int): String = delegate.removeAt(index)
            override fun set(index: Int, element: String): String = delegate.set(index, element).also { delegate.sort() }
        }

        val list: MutableList<String> get() = SortingList(_list)

        protected abstract var _string: String?

        var string: String?
            get() = _string
            set(value) {_string = value?.removeSurrounding("\"")}
    }

    @Test
    fun `check error message for underlying managed properties`() {
        val decorator = DslDecorator(listOf(SupportedPropertyType.Var.String, SupportedPropertyType.Collection.List))
        val decorated = decorator.decorate(WithCustomImpl::class)
        val o = decorated.getDeclaredConstructor(DslServices::class.java).newInstance(dslServices)
        o.string = "a"
        o.list += listOf("c", "a", "f")
        assertThat(o.string).isEqualTo("a")
        assertThat(o.list).containsExactly("a", "c", "f").inOrder()

        o.list += "e"
        assertThat(o.list).containsExactly("a", "c", "e", "f").inOrder()

        (o as Lockable).lock()
        val failure = assertFailsWith<AgpDslLockedException> {
            o.string = "b"
        }
        assertThat(failure).hasMessageThat().contains("It is too late to set string")
        assertThat(o.string).isEqualTo("a")

        val failure2 = assertFailsWith<AgpDslLockedException> {
            o.list += "b"
        }
        assertThat(failure2).hasMessageThat().contains("It is too late to modify list")
        assertThat(o.list).containsExactly("a", "c", "e", "f").inOrder()
    }
}
