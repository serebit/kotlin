/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "SingleThreadMarkAndSweep.hpp"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "../GlobalData.hpp"
#include "../ObjectOps.hpp"
#include "../TestSupport.hpp"
#include "ObjectTestSupport.hpp"

using namespace kotlin;

// These tests can only work if `GC` is `SingleThreadMarkAndSweep`.
// TODO: Extracting GC into a separate module will help with this.

namespace {

struct Payload {
    ObjHeader* field1;
    ObjHeader* field2;
    ObjHeader* field3;

    static constexpr std::array kFields = {
            &Payload::field1,
            &Payload::field2,
            &Payload::field3,
    };
};

test_support::TypeInfoHolder typeHolder{test_support::TypeInfoHolder::ObjectBuilder<Payload>()};

class Global : private Pinned {
public:
    explicit Global(mm::ThreadData& threadData) {
        mm::GlobalsRegistry::Instance().RegisterStorageForGlobal(&threadData, &location_);
        mm::AllocateObject(&threadData, typeHolder.typeInfo(), &location_);
    }

    ObjHeader* header() { return location_; }

    test_support::Object<Payload>& operator*() { return test_support::Object<Payload>::FromObjHeader(location_); }
    test_support::Object<Payload>& operator->() { return test_support::Object<Payload>::FromObjHeader(location_); }

private:
    ObjHeader* location_;
};

class Stack : private Pinned {
public:
    explicit Stack(mm::ThreadData& threadData) { mm::AllocateObject(&threadData, typeHolder.typeInfo(), holder_.slot()); }

    ObjHeader* header() { return holder_.obj(); }

    test_support::Object<Payload>& operator*() { return test_support::Object<Payload>::FromObjHeader(holder_.obj()); }
    test_support::Object<Payload>& operator->() { return test_support::Object<Payload>::FromObjHeader(holder_.obj()); }

private:
    ObjHolder holder_;
};

test_support::Object<Payload>& Allocate(mm::ThreadData& threadData) {
    ObjHolder holder;
    mm::AllocateObject(&threadData, typeHolder.typeInfo(), holder.slot());
    return test_support::Object<Payload>::FromObjHeader(holder.obj());
}

KStdVector<ObjHeader*> Alive(mm::ThreadData& threadData) {
    KStdVector<ObjHeader*> objects;
    for (auto node : threadData.objectFactoryThreadQueue()) {
        objects.push_back(node.IsArray() ? node.GetArrayHeader()->obj() : node.GetObjHeader());
    }
    for (auto node : mm::GlobalData::Instance().objectFactory().Iter()) {
        objects.push_back(node.IsArray() ? node.GetArrayHeader()->obj() : node.GetObjHeader());
    }
    return objects;
}

class SingleThreadMarkAndSweepTest : public testing::Test {
public:
    ~SingleThreadMarkAndSweepTest() {
        mm::GlobalsRegistry::Instance().ClearForTests();
        mm::GlobalData::Instance().objectFactory().ClearForTests();
    }
};

} // namespace

TEST_F(SingleThreadMarkAndSweepTest, RootSet) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        Global global1{threadData};
        Global global2{threadData};
        Stack stack1{threadData};
        Stack stack2{threadData};

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), stack1.header(), stack2.header()));

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), stack1.header(), stack2.header()));
    });
}

TEST_F(SingleThreadMarkAndSweepTest, InterconnectedRootSet) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        Global global1{threadData};
        Global global2{threadData};
        Stack stack1{threadData};
        Stack stack2{threadData};

        global1->field1 = stack1.header();
        global1->field2 = global1.header();
        global1->field3 = global2.header();
        global2->field1 = global1.header();
        stack1->field1 = global1.header();
        stack1->field2 = stack1.header();
        stack1->field3 = stack2.header();
        stack2->field1 = stack1.header();

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), stack1.header(), stack2.header()));

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), stack1.header(), stack2.header()));
    });
}

TEST_F(SingleThreadMarkAndSweepTest, FreeObjects) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        auto& object1 = Allocate(threadData);
        auto& object2 = Allocate(threadData);

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(object1.header(), object2.header()));

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre());
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectReferencedFromRootSet) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        Global global{threadData};
        Stack stack{threadData};
        auto& object1 = Allocate(threadData);
        auto& object2 = Allocate(threadData);
        auto& object3 = Allocate(threadData);
        auto& object4 = Allocate(threadData);

        global->field1 = object1.header();
        object1->field1 = object2.header();
        stack->field1 = object3.header();
        object3->field1 = object4.header();

        ASSERT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));

        threadData.gc().PerformFullGC();

        EXPECT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectsWithCycles) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        Global global{threadData};
        Stack stack{threadData};
        auto& object1 = Allocate(threadData);
        auto& object2 = Allocate(threadData);
        auto& object3 = Allocate(threadData);
        auto& object4 = Allocate(threadData);
        auto& object5 = Allocate(threadData);
        auto& object6 = Allocate(threadData);

        global->field1 = object1.header();
        object1->field1 = object2.header();
        object2->field1 = object1.header();
        stack->field1 = object3.header();
        object3->field1 = object4.header();
        object4->field1 = object3.header();
        object5->field1 = object6.header();
        object6->field1 = object5.header();

        ASSERT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header(),
                        object5.header(), object6.header()));

        threadData.gc().PerformFullGC();

        EXPECT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectsWithCyclesIntoRootSet) {
    mm::RunInNewThread([](mm::ThreadData& threadData) {
        Global global{threadData};
        Stack stack{threadData};
        auto& object1 = Allocate(threadData);
        auto& object2 = Allocate(threadData);

        global->field1 = object1.header();
        object1->field1 = global.header();
        stack->field1 = object2.header();
        object2->field1 = stack.header();

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(global.header(), stack.header(), object1.header(), object2.header()));

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre(global.header(), stack.header(), object1.header(), object2.header()));
    });
}

// TODO: Arrays
// TODO: Primitive arrays
// TODO: Finalizers
// TODO: Run GC after GC.
// TODO: Check colors.
