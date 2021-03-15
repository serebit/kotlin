// !LANGUAGE: +ProhibitInvisibleAbstractMethodsInSuperclasses
// MODULE: base
// FILE: Base.kt
package base

abstract class Base {
    fun foo(): String {
        return internalFoo()
    }
    internal abstract fun internalFoo(): String
}

open class BaseWithOverride : Base() {
    override fun internalFoo(): String = ""
}

// MODULE: intermediate(base)
// FILE: Intermediate.kt
package intermediate
import base.*

abstract class Intermediate : Base()

// MODULE: impl(base, intermediate)
// FILE: Impl.kt
package impl
import base.*
import intermediate.*

<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>class ImplDirectFromBase<!> : Base()

<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>object ImplObjDirectFromBase<!> : Base()

class ImplDirectFromBaseWithOverride : BaseWithOverride()

class ImplDirectFromBaseWithOverrid : Base() {
    override fun internalFoo(): String = ""
}

<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>class ImplViaIntermediate<!> : Intermediate()

fun foo() {
    ImplDirectFromBase().foo()
    ImplObjDirectFromBase.foo()
    ImplDirectFromBaseWithOverride().foo()
    ImplViaIntermediate().foo()
}
