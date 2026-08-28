package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * github.com/floci-io/floci/issues/2396 - AWS::Scheduler::ScheduleGroup fell through to the
 * generic stub (fake physical id, no backing call), so a stack reporting CREATE_COMPLETE never
 * actually created the group. These tests exercise the provisioner in isolation.
 */
class SchedulerScheduleGroupCfnProvisionerTest {

    private final SchedulerService schedulerService = mock(SchedulerService.class);
    private final SchedulerScheduleGroupCfnProvisioner provisioner =
            new SchedulerScheduleGroupCfnProvisioner(schedulerService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType("AWS::Scheduler::ScheduleGroup");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static ScheduleGroup group(String name, Map<String, String> tags) {
        ScheduleGroup g = new ScheduleGroup(name,
                "arn:aws:scheduler:us-east-1:000000000000:schedule-group/" + name,
                "ACTIVE", Instant.now(), Instant.now());
        g.getTags().putAll(tags);
        return g;
    }

    @Test
    void createsGroupAndSetsPhysicalIdAndArn() {
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1")))
                .thenReturn(group("my-group", Map.of()));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx());

        assertEquals("my-group", r.getPhysicalId());
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule-group/my-group",
                r.getAttributes().get("Arn"));
    }

    @Test
    void withoutNameGeneratesPhysicalName() {
        when(schedulerService.createScheduleGroup(anyString(), any(), eq("us-east-1")))
                .thenAnswer(inv -> group(inv.getArgument(0), Map.of()));
        StackResource r = resource("MyGroup");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertTrue(r.getPhysicalId().startsWith("my-stack-MyGroup-"),
                "generated name should follow <stack>-<logicalId>-<suffix> but was: " + r.getPhysicalId());
        assertTrue(r.getPhysicalId().length() <= 64, "schedule group names are capped at 64 characters");
    }

    @Test
    void passesResolvedTagsToCreateScheduleGroup() {
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1")))
                .thenReturn(group("my-group", Map.of("Env", "prod")));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");
        ObjectNode tag = mapper.createObjectNode().put("Key", "Env").put("Value", "prod");
        props.set("Tags", mapper.createArrayNode().add(tag));

        provisioner.provision(r, props, ctx());

        verify(schedulerService).createScheduleGroup("my-group", Map.of("Env", "prod"), "us-east-1");
    }

    @Test
    void sameStackRetryAdoptsExistingGroup() {
        // The physical id already recorded on this resource (from an earlier attempt) matches the
        // name this attempt resolves to, so a ConflictException on create means this attempt's own
        // group already exists - adopt it instead of failing the stack.
        when(schedulerService.createScheduleGroup(eq("my-group"), any(), eq("us-east-1")))
                .thenThrow(new AwsException("ConflictException", "already exists", 409));
        when(schedulerService.getScheduleGroup("my-group", "us-east-1"))
                .thenReturn(group("my-group", Map.of()));
        StackResource r = resource("MyGroup");
        r.setPhysicalId("my-group");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-group");

        provisioner.provision(r, props, ctx());

        assertEquals("my-group", r.getPhysicalId());
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule-group/my-group",
                r.getAttributes().get("Arn"));
    }

    @Test
    void conflictForADifferentPhysicalIdIsNotAdopted() {
        // No prior physical id recorded (fresh create) colliding with someone else's group of the
        // same name: not this attempt's retry, must fail rather than silently adopting a stranger's
        // group.
        when(schedulerService.createScheduleGroup(eq("taken-name"), any(), eq("us-east-1")))
                .thenThrow(new AwsException("ConflictException", "already exists", 409));
        StackResource r = resource("MyGroup");
        ObjectNode props = mapper.createObjectNode().put("Name", "taken-name");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ConflictException", thrown.getErrorCode());
        verify(schedulerService, never()).getScheduleGroup(anyString(), anyString());
    }

    @Test
    void deleteDelegatesToService() {
        provisioner.delete("AWS::Scheduler::ScheduleGroup", "my-group", "us-east-1");
        verify(schedulerService).deleteScheduleGroup("my-group", "us-east-1");
    }

    @Test
    void deleteAlreadyGoneIsTreatedAsSuccess() {
        doThrowNotFoundOnDelete("my-group");
        provisioner.delete("AWS::Scheduler::ScheduleGroup", "my-group", "us-east-1");
    }

    private void doThrowNotFoundOnDelete(String name) {
        org.mockito.Mockito.doThrow(new AwsException("ResourceNotFoundException", "not found", 404))
                .when(schedulerService).deleteScheduleGroup(name, "us-east-1");
    }
}
