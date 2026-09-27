package com.tracel.engine.ownership

import com.tracel.annotations.LeaseStore
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext

/** In-memory [LotLeaseRegistry]. Mutators live on the generated [InMemoryLotLeaseRegistryStore]. */
@LeaseStore
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryLotLeaseRegistry : InMemoryLotLeaseRegistryStore()
