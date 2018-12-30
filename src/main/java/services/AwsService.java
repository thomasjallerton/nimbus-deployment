package services;

import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.model.Export;
import com.amazonaws.services.cloudformation.model.ListExportsRequest;
import com.amazonaws.services.cloudformation.model.ListExportsResult;
import org.apache.maven.plugin.logging.Log;

import java.util.List;

public class AwsService {

    Log logger;

    public AwsService(Log logger) {
        this.logger = logger;
    }

    public String findBucketName(AmazonCloudFormation client, int count) {
        ListExportsRequest listExportsRequest = new ListExportsRequest();

        ListExportsResult exportsResults = client.listExports(listExportsRequest);
        List<Export> exports = exportsResults.getExports();

        for (Export export : exports) {
            if (export.getName().equals("NimbusProjectBucketName")) {
                return export.getValue();
            }
        }

        if (count == 0) {
            logger.warn("Couldn't find bucket name");
            return "";
        }

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.print("*");
        return findBucketName(client, --count);
    }
}
