package nz.co.mixport.customsvision.data

import java.io.IOException

sealed class CustomsSyncException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class CustomsSyncConfigurationException(message: String) : CustomsSyncException(message)

class CustomsSyncRemoteException(
    message: String,
    cause: Throwable? = null,
) : CustomsSyncException(message, cause)

class CustomsSyncTransportException(
    message: String,
    cause: Throwable? = null,
) : CustomsSyncException(message, cause)

class CustomsSyncParsingException(
    message: String,
    cause: Throwable? = null,
) : CustomsSyncException(message, cause)
