package io.github.zhh2001.jp4.error;

import io.github.zhh2001.jp4.entity.UpdateFailure;
import io.github.zhh2001.jp4.types.ElectionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionHierarchyTest {

    @Test
    void allConcreteSubclassesExtendBase() {
        assertTrue(P4RuntimeException.class.isAssignableFrom(P4ConnectionException.class));
        assertTrue(P4RuntimeException.class.isAssignableFrom(P4PipelineException.class));
        assertTrue(P4RuntimeException.class.isAssignableFrom(P4OperationException.class));
        assertTrue(P4ConnectionException.class.isAssignableFrom(P4ArbitrationLost.class));
    }

    @Test
    void allAreUnchecked() {
        assertTrue(RuntimeException.class.isAssignableFrom(P4RuntimeException.class));
    }

    @Test
    void p4ArbitrationLostCarriesElectionIds() {
        P4ArbitrationLost e = new P4ArbitrationLost("lost",
                ElectionId.of(1), ElectionId.of(2));
        assertEquals(ElectionId.of(1), e.ourElectionId());
        assertEquals(ElectionId.of(2), e.currentPrimaryElectionId());
    }

    @Test
    void p4OperationExceptionCarriesFailures() {
        P4OperationException e = new P4OperationException(
                "batch failed",
                OperationType.INSERT,
                ErrorCode.ALREADY_EXISTS,
                List.of(new UpdateFailure(0, ErrorCode.ALREADY_EXISTS, "dup")));
        assertEquals(OperationType.INSERT, e.operationType());
        assertEquals(ErrorCode.ALREADY_EXISTS, e.errorCode());
        assertEquals(1, e.failures().size());
    }

    @Test
    void errorCodeFromGrpcCode() {
        assertEquals(ErrorCode.OK, ErrorCode.fromGrpcCode(0));
        assertEquals(ErrorCode.NOT_FOUND, ErrorCode.fromGrpcCode(5));
        assertEquals(ErrorCode.UNKNOWN, ErrorCode.fromGrpcCode(999));
        assertEquals(ErrorCode.UNKNOWN, ErrorCode.fromGrpcCode(-1));
    }
}
