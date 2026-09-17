package unipatch.overlaycore;

/** Register plan for injected code that preserves original parameter registers. */
public final class RegisterAllocationPlan {
    public final int originalRegisterCount;
    public final int firstScratchRegister;
    public final int expandedRegisterCount;

    private RegisterAllocationPlan(int originalRegisterCount, int firstScratchRegister, int expandedRegisterCount) {
        this.originalRegisterCount = originalRegisterCount;
        this.firstScratchRegister = firstScratchRegister;
        this.expandedRegisterCount = expandedRegisterCount;
    }

    public static RegisterAllocationPlan create(int originalRegisterCount, int parameterRegisters, int scratchRegisters) {
        if (originalRegisterCount < 0 || parameterRegisters < 0 || scratchRegisters <= 0) {
            throw new IllegalArgumentException("Invalid register allocation request");
        }
        return new RegisterAllocationPlan(originalRegisterCount, originalRegisterCount,
                originalRegisterCount + parameterRegisters + scratchRegisters);
    }
}
