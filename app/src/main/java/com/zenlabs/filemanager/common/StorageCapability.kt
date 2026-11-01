package com.zenlabs.filemanager.common

/** Explicit operations advertised by a storage backend or content holder. */
enum class StorageCapability {
    LIST,
    READ,
    WRITE,
    CREATE,
    RENAME,
    MOVE,
    COPY,
    DELETE,
    DOWNLOAD,
    UPLOAD,
    SEARCH,
    SHARE,
    QUOTA
}
