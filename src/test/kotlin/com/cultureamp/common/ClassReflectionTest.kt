package com.cultureamp.common

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ClassReflectionTest : DescribeSpec({
    describe("sealedSubclasses") {
        it("can return immediate sealed sub-classes") {
            A::class.sealedSubclasses shouldBe listOf(A1::class, A2::class)
        }

        it("can't return immediate interface sub-classes") {
            IB::class.sealedSubclasses shouldBe emptyList()
        }

        it("works when the sealed class implements an interface") {
            IC::class.sealedSubclasses shouldBe emptyList()
            C::class.sealedSubclasses shouldBe listOf(C1::class, C2::class)
        }

        it("doesn't fetch nested subclasses") {
            D::class.sealedSubclasses shouldBe listOf(D1::class, DSub::class)
        }

        it("doesn't return anything for non-sealed classes") {
            E::class.sealedSubclasses shouldBe emptyList()
        }

        it("returns nothing for concrete classes") {
            A1::class.sealedSubclasses shouldBe emptyList()
            A2::class.sealedSubclasses shouldBe emptyList()
            B1::class.sealedSubclasses shouldBe emptyList()
            B2::class.sealedSubclasses shouldBe emptyList()
            C1::class.sealedSubclasses shouldBe emptyList()
            C2::class.sealedSubclasses shouldBe emptyList()
            D1::class.sealedSubclasses shouldBe emptyList()
            D2::class.sealedSubclasses shouldBe emptyList()
            E1::class.sealedSubclasses shouldBe emptyList()
            E2::class.sealedSubclasses shouldBe emptyList()
        }
    }

    describe("isFinal") {
        it("sealed classes aren't final") {
            A::class.isFinal shouldBe false
            C::class.isFinal shouldBe false
            D::class.isFinal shouldBe false
            DSub::class.isFinal shouldBe false
        }

        it("data classes are final") {
            A1::class.isFinal shouldBe true
            A2::class.isFinal shouldBe true
            B1::class.isFinal shouldBe true
            B2::class.isFinal shouldBe true
            C1::class.isFinal shouldBe true
            C2::class.isFinal shouldBe true
            D1::class.isFinal shouldBe true
            D2::class.isFinal shouldBe true
        }

        it("interfaces aren't final") {
            IB::class.isFinal shouldBe false
            IC::class.isFinal shouldBe false
        }

        it("open classes aren't final") {
            E::class.isFinal shouldBe false
        }

        it("normal concrete classes are final") {
            E1::class.isFinal shouldBe true
            E2::class.isFinal shouldBe true
        }
    }

    describe("asNestedSealedConcreteClasses") {
        it("can return immediate sealed sub-classes") {
            A::class.asNestedSealedConcreteClasses() shouldBe listOf(A1::class, A2::class)
        }

        it("can't return immediate interface sub-classes") {
            IB::class.asNestedSealedConcreteClasses() shouldBe emptyList()
        }

        it("works when the sealed class implements an interface") {
            IC::class.asNestedSealedConcreteClasses() shouldBe emptyList()
            C::class.asNestedSealedConcreteClasses() shouldBe listOf(C1::class, C2::class)
        }

        it("fetches nested subclasses recursively") {
            D::class.asNestedSealedConcreteClasses() shouldBe listOf(D1::class, D2::class)
        }

        it("fetches nothing for open classes") {
            E::class.asNestedSealedConcreteClasses() shouldBe emptyList()
        }

        it("normal concrete [data]classes just return themselves") {
            A1::class.asNestedSealedConcreteClasses() shouldBe listOf(A1::class)
            A2::class.asNestedSealedConcreteClasses() shouldBe listOf(A2::class)
            B1::class.asNestedSealedConcreteClasses() shouldBe listOf(B1::class)
            B2::class.asNestedSealedConcreteClasses() shouldBe listOf(B2::class)
            C1::class.asNestedSealedConcreteClasses() shouldBe listOf(C1::class)
            C2::class.asNestedSealedConcreteClasses() shouldBe listOf(C2::class)
            D1::class.asNestedSealedConcreteClasses() shouldBe listOf(D1::class)
            D2::class.asNestedSealedConcreteClasses() shouldBe listOf(D2::class)
            E1::class.asNestedSealedConcreteClasses() shouldBe listOf(E1::class)
            E2::class.asNestedSealedConcreteClasses() shouldBe listOf(E2::class)
        }

        it("copes with large nested structures of all different types") {
            IF::class.asNestedSealedConcreteClasses() shouldBe emptyList()
            F::class.asNestedSealedConcreteClasses() shouldBe listOf(F1::class, F2::class, F3::class, F6::class, F7::class)
            F1::class.asNestedSealedConcreteClasses() shouldBe listOf(F1::class)
            FSub::class.asNestedSealedConcreteClasses() shouldBe listOf(F2::class, F3::class, F6::class, F7::class)
            F2::class.asNestedSealedConcreteClasses() shouldBe listOf(F2::class)
            F3::class.asNestedSealedConcreteClasses() shouldBe listOf(F3::class)
            F4::class.asNestedSealedConcreteClasses() shouldBe emptyList()
            F5::class.asNestedSealedConcreteClasses() shouldBe listOf(F5::class)
            FSubSub::class.asNestedSealedConcreteClasses() shouldBe listOf(F6::class, F7::class)
            FSubSubSub::class.asNestedSealedConcreteClasses() shouldBe listOf(F6::class, F7::class)
            FSubSubSubSub::class.asNestedSealedConcreteClasses() shouldBe listOf(F6::class, F7::class)
            FSubSubSubSubSub::class.asNestedSealedConcreteClasses() shouldBe listOf(F6::class, F7::class)
            F6::class.asNestedSealedConcreteClasses() shouldBe listOf(F6::class)
            FSubSubSubSubSubSub::class.asNestedSealedConcreteClasses() shouldBe listOf(F7::class)
            F7::class.asNestedSealedConcreteClasses() shouldBe listOf(F7::class)
        }
    }
})

sealed class A
data class A1(val value: String) : A()
data class A2(val value: Int) : A()

interface IB
data class B1(val value: String) : IB
data class B2(val value: Int) : IB

interface IC
sealed class C : IC
data class C1(val value: String) : C()
data class C2(val value: Int) : C()

sealed class D
data class D1(val value: String) : D()
sealed class DSub : D()
data class D2(val value: Int) : DSub()

open class E
class E1 : E()
class E2 : E()

interface IF
sealed class F : IF
data class F1(val value: String) : F()
sealed class FSub : F()
data class F2(val value: Int) : FSub()
class F3(val value: Double) : FSub()
open class F4 : FSub()
data class F5(val value: Float): F4()
sealed class FSubSub : FSub()
sealed class FSubSubSub : FSubSub()
sealed class FSubSubSubSub : FSubSubSub()
sealed class FSubSubSubSubSub : FSubSubSubSub()
data class F6(val value: String) : FSubSubSubSubSub()
sealed class FSubSubSubSubSubSub : FSubSubSubSubSub()
data class F7(val value: String) : FSubSubSubSubSubSub()
