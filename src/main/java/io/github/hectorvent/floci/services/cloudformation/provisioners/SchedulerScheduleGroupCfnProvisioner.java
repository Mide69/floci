package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Scheduler::ScheduleGroup}. Previously unhandled, so
 * the resource type fell through to the generic stub: the stack reported CREATE_COMPLETE with a
 * random physical id and no group was ever created in SchedulerService (issue #2396).
 */
@ApplicationScoped
public class SchedulerScheduleGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(SchedulerScheduleGroupCfnProvisioner.class);

    private final SchedulerService schedulerService;

    @Inject
    public SchedulerScheduleGroupCfnProvisioner(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Scheduler::ScheduleGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            // No explicit name and this resource already has a physical id: keep it across updates
            // instead of generating a fresh random one each retry, same as LogGroup/Queue do.
            name = r.getPhysicalId() != null
                    ? r.getPhysicalId()
                    : ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }
        Map<String, String> tags = new HashMap<>();
        if (props != null && props.has("Tags") && props.get("Tags").isArray()) {
            for (JsonNode tag : props.get("Tags")) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.put(key, ctx.engine().resolve(tag.path("Value")));
                }
            }
        }

        ScheduleGroup group;
        try {
            group = schedulerService.createScheduleGroup(name, tags, ctx.region());
        } catch (AwsException e) {
            if (!"ConflictException".equals(e.getErrorCode()) || !name.equals(r.getPhysicalId())) {
                throw e;
            }
            // Same-stack create-retry: CloudFormation only retries a resource under the physical id
            // it previously assigned, so a conflict on that exact name means this logical resource's
            // own group already exists from an earlier attempt. Adopt it instead of failing the whole
            // stack on a retry of a step that already succeeded.
            group = schedulerService.getScheduleGroup(name, ctx.region());
            if (!tags.isEmpty()) {
                schedulerService.tagScheduleGroup(name, ctx.region(), tags);
            }
        }
        r.setPhysicalId(group.getName());
        r.getAttributes().put("Arn", group.getArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        try {
            schedulerService.deleteScheduleGroup(physicalId, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Schedule group already gone, treating as deleted: {0}", physicalId);
        }
    }
}
