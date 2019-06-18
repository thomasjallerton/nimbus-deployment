package configuration

const val DEPLOYMENT_BUCKET_NAME = "NimbusDeploymentBucketName"
const val STACK_UPDATE_FILE = "nimbus/cloudformation-stack-update"
const val STACK_CREATE_FILE = "nimbus/cloudformation-stack-create"
const val NIMBUS_STATE = "nimbus/nimbus-state.json"
const val MOST_RECENT_DEPLOYMENT = ".nimbus/latest-deployment"
const val S3_DEPLOYMENT_PATH = "DeploymentInformation"
val EMPTY_CLIENTS = setOf(
        "com/nimbusframework/nimbuscore/clients/document/EmptyDocumentStoreClient.class",
        "com/nimbusframework/nimbuscore/clients/keyvalue/EmptyKeyValueStoreClient.class",
        "com/nimbusframework/nimbuscore/clients/file/EmptyFileStorageClient.class",
        "com/nimbusframework/nimbuscore/clients/function/EmptyEnvironmentVariableClient.class",
        "com/nimbusframework/nimbuscore/clients/function/EmptyBasicServerlessFunctionClient.class",
        "com/nimbusframework/nimbuscore/clients/notification/EmptyNotificationClient.class",
        "com/nimbusframework/nimbuscore/clients/queue/EmptyQueueClient.class",
        "com/nimbusframework/nimbuscore/clients/rdbms/EmptyDatabaseClient.class",
        "com/nimbusframework/nimbuscore/clients/websocket/EmptyServerlessFunctionWebSocketClient.class")