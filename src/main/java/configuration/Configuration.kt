package configuration

const val DEPLOYMENT_BUCKET_NAME = "NimbusDeploymentBucketName"
const val STACK_UPDATE_FILE = "nimbus/cloudformation-stack-update"
const val STACK_CREATE_FILE = "nimbus/cloudformation-stack-create"
const val NIMBUS_STATE = "nimbus/nimbus-state.json"
const val MOST_RECENT_DEPLOYMENT = ".nimbus/latest-deployment"
const val S3_DEPLOYMENT_PATH = "DeploymentInformation"
val EMPTY_CLIENTS = setOf(
        "com.nimbusframework.nimbuscore.clients.document.EmptyDocumentStoreClient",
        "com.nimbusframework.nimbuscore.clients.keyvalue.EmptyKeyValueStoreClient",
        "com.nimbusframework.nimbuscore.clients.file.EmptyFileStorageClient",
        "com.nimbusframework.nimbuscore.clients.function.EmptyEnvironmentVariableClient",
        "com.nimbusframework.nimbuscore.clients.function.EmptyBasicServerlessFunctionClient",
        "com.nimbusframework.nimbuscore.clients.notification.EmptyNotificationClient",
        "com.nimbusframework.nimbuscore.clients.queue.EmptyQueueClient",
        "com.nimbusframework.nimbuscore.clients.rdbms.EmptyDatabaseClient",
        "com.nimbusframework.nimbuscore.clients.websocket.EmptyServerlessFunctionWebSocketClient")