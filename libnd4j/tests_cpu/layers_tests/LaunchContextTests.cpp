//
// Created by raver on 5/19/2018.
//

#include "testlayers.h"
#include <pointercast.h>
#include <Nd4j.h>
#include <NDArray.h>
#include <array/LaunchContext.h>

using namespace nd4j;
using namespace nd4j::ops;

class LaunchContextTests : public testing::Test {

};

TEST_F(LaunchContextTests,  Basic_Test_1) {
    //
//    LaunchContext ctx;
//    nd4j_printf("Context was created successfully.\n", "");
    ASSERT_TRUE(true);
}