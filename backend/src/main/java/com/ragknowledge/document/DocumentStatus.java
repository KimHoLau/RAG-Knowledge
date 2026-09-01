package com.ragknowledge.document;

public enum DocumentStatus {

    /** 解析与向量化进行中 */
    PROCESSING,

    /** 已完成入库 */
    COMPLETED,

    /** 入库失败（见 errorMessage） */
    FAILED
}
