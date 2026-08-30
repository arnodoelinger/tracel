package com.tracel.engine.ownership

import com.tracel.annotations.Lease

/** Schema marker. KSP emits [LotLease], [LeaseAcquisition], and [LotLeaseRegistry] next to this. */
@Lease
public object Lease
