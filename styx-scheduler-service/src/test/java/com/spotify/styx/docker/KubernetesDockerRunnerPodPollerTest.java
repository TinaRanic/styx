/*-
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx.docker;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.spotify.styx.docker.KubernetesDockerRunner.KubernetesSecretSpec;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.monitoring.Stats;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.StateData;
import com.spotify.styx.state.StateManager;
import com.spotify.styx.testdata.TestData;
import com.spotify.styx.util.Debug;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.ListMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesDockerRunnerPodPollerTest {

  private static final WorkflowInstance WORKFLOW_INSTANCE =
      WorkflowInstance.create(TestData.WORKFLOW_ID, "foo");
  private static final WorkflowInstance WORKFLOW_INSTANCE_2 =
      WorkflowInstance.create(TestData.WORKFLOW_ID_2, "bar");
  private static final DockerRunner.RunSpec RUN_SPEC =
      DockerRunner.RunSpec.simple("eid1", "busybox");
  private static final DockerRunner.RunSpec RUN_SPEC_2 =
      DockerRunner.RunSpec.simple("eid2", "busybox");
  private final static KubernetesSecretSpec SECRET_SPEC = KubernetesSecretSpec.builder().build();

  private static final String POD_NAME = RUN_SPEC.executionId();
  private static final String POD_NAME_2 = RUN_SPEC_2.executionId();

  @Mock
  NamespacedKubernetesClient k8sClient;
  @Mock
  MixedOperation<Pod, PodList, DoneablePod, PodResource<Pod, DoneablePod>> pods;
  PodList podList;
  @Mock PodResource<Pod, DoneablePod> namedPod1;
  @Mock PodResource<Pod, DoneablePod> namedPod2;
  @Mock PodStatus podStatus1;
  @Mock PodStatus podStatus2;
  @Mock ContainerStatus containerStatus1;
  @Mock ContainerStatus containerStatus2;
  @Mock ContainerState containerState1;
  @Mock ContainerState containerState2;
  @Mock ContainerStateTerminated containerStateTerminated;
  @Mock
  StateManager stateManager;
  @Mock
  Stats stats;

  @Mock KubernetesGCPServiceAccountSecretManager serviceAccountSecretManager;
  @Mock Debug debug;

  KubernetesDockerRunner kdr;

  @Before
  public void setUp() {
    when(debug.get()).thenReturn(false);

    when(k8sClient.inNamespace(any(String.class))).thenReturn(k8sClient);
    when(k8sClient.pods()).thenReturn(pods);

    kdr = new KubernetesDockerRunner(k8sClient, stateManager, stats, serviceAccountSecretManager, debug);
    podList = new PodList();
    podList.setMetadata(new ListMeta());
    podList.getMetadata().setResourceVersion("4711");
  }

  @Test
  public void shouldSendRunErrorWhenPodForRunningWFIDoesntExist() {
    Pod createdPod = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE, RUN_SPEC, SECRET_SPEC);
    podList.setItems(Arrays.asList(createdPod));
    createdPod.setStatus(podStatus1);
    when(podStatus1.getPhase()).thenReturn("Pending");
    when(k8sClient.pods().list()).thenReturn(podList);
    when(namedPod1.get()).thenReturn(createdPod);
    when(namedPod2.get()).thenReturn(null);
    when(k8sClient.pods().withName(POD_NAME)).thenReturn(namedPod1);
    when(k8sClient.pods().withName(POD_NAME_2)).thenReturn(namedPod2);
    setupActiveInstances(RunState.State.RUNNING, POD_NAME, POD_NAME_2);

    kdr.tryPollPods();

    verify(stateManager, times(1)).receiveIgnoreClosed(
        Event.runError(WORKFLOW_INSTANCE_2, "No pod associated with this instance"), -1);
  }

  @Test
  public void shouldNotSendRunErrorWhenPodForRunningWFIExists() {
    Pod createdPod = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE, RUN_SPEC, SECRET_SPEC);
    Pod createdPod2 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE_2, RUN_SPEC, SECRET_SPEC);
    when(podStatus1.getPhase()).thenReturn("Pending");
    when(podStatus2.getPhase()).thenReturn("Pending");
    createdPod.setStatus(podStatus1);
    createdPod2.setStatus(podStatus2);
    podList.setItems(Arrays.asList(createdPod, createdPod2));
    when(k8sClient.pods().list()).thenReturn(podList);
    when(namedPod1.get()).thenReturn(createdPod);
    when(namedPod2.get()).thenReturn(createdPod2);
    when(k8sClient.pods().withName(POD_NAME)).thenReturn(namedPod1);
    when(k8sClient.pods().withName(POD_NAME_2)).thenReturn(namedPod2);
    setupActiveInstances(RunState.State.RUNNING, POD_NAME, POD_NAME_2);

    kdr.tryPollPods();

    verify(stateManager).getActiveStates();
    verifyNoMoreInteractions(stateManager);
  }

  @Test
  public void shouldHandleEmptyPodList() {
    when(k8sClient.pods().list()).thenReturn(podList);
    when(namedPod1.get()).thenReturn(null);
    when(k8sClient.pods().withName(anyString())).thenReturn(namedPod1);
    setupActiveInstances(RunState.State.RUNNING, POD_NAME, POD_NAME_2);

    kdr.tryPollPods();

    verify(stateManager, times(1)).receiveIgnoreClosed(
        Event.runError(WORKFLOW_INSTANCE, "No pod associated with this instance"), -1);
    verify(stateManager, times(1)).receiveIgnoreClosed(
        Event.runError(WORKFLOW_INSTANCE_2, "No pod associated with this instance"), -1);
  }

  @Test
  public void shouldNotSendErrorEventForInstancesNotInRunningState() {
    when(k8sClient.pods().list()).thenReturn(podList);
    setupActiveInstances(RunState.State.SUBMITTED, POD_NAME, POD_NAME_2);

    kdr.tryPollPods();

    verify(stateManager).getActiveStates();
    verifyNoMoreInteractions(stateManager);
  }

  @Test
  public void shouldDeleteUnwantedStyxPods() {
    final Pod createdPod1 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE, RUN_SPEC, SECRET_SPEC);
    final Pod createdPod2 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE_2, RUN_SPEC_2, SECRET_SPEC);

    podList.setItems(Arrays.asList(createdPod1, createdPod2));
    when(k8sClient.pods().list()).thenReturn(podList);
    when(k8sClient.pods().withName(RUN_SPEC.executionId())).thenReturn(namedPod1);
    when(k8sClient.pods().withName(RUN_SPEC_2.executionId())).thenReturn(namedPod2);
    when(namedPod1.get()).thenReturn(createdPod1);
    when(namedPod2.get()).thenReturn(createdPod2);
    when(stateManager.getActiveState(any())).thenReturn(Optional.empty());

    createdPod1.setStatus(podStatus1);
    when(podStatus1.getContainerStatuses()).thenReturn(ImmutableList.of(containerStatus1));
    when(containerStatus1.getName()).thenReturn(RUN_SPEC.executionId());
    when(containerStatus1.getState()).thenReturn(containerState1);
    when(containerState1.getTerminated()).thenReturn(containerStateTerminated);

    createdPod2.setStatus(podStatus2);
    when(podStatus2.getContainerStatuses()).thenReturn(ImmutableList.of(containerStatus2));
    when(containerStatus2.getName()).thenReturn(RUN_SPEC_2.executionId());
    when(containerStatus2.getState()).thenReturn(containerState2);
    when(containerState2.getTerminated()).thenReturn(containerStateTerminated);

    kdr.tryPollPods();

    verify(namedPod1).delete();
    verify(namedPod2).delete();
  }

  @Test
  public void shouldNotDeleteUnwantedStyxPodsIfDebugEnabled() {
    when(debug.get()).thenReturn(true);

    final Pod createdPod1 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE, RUN_SPEC, SECRET_SPEC);
    final Pod createdPod2 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE_2, RUN_SPEC_2, SECRET_SPEC);

    podList.setItems(Arrays.asList(createdPod1, createdPod2));
    when(k8sClient.pods().list()).thenReturn(podList);
    when(k8sClient.pods().withName(RUN_SPEC.executionId())).thenReturn(namedPod1);
    when(k8sClient.pods().withName(RUN_SPEC_2.executionId())).thenReturn(namedPod2);
    when(namedPod1.get()).thenReturn(createdPod1);
    when(namedPod2.get()).thenReturn(createdPod2);
    createdPod1.setStatus(new PodStatusBuilder().withContainerStatuses().build());
    createdPod2.setStatus(new PodStatusBuilder().withContainerStatuses().build());

    kdr.tryPollPods();

    verifyPodNeverDeleted(namedPod1);
    verifyPodNeverDeleted(namedPod2);
  }

  @Test
  public void shouldNotDeleteUnwantedNonStyxPods() {
    final Pod createdPod1 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE, RUN_SPEC, SECRET_SPEC);
    final Pod createdPod2 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE_2, RUN_SPEC_2, SECRET_SPEC);

    createdPod1.getMetadata().getAnnotations().remove("styx-workflow-instance");
    createdPod2.getMetadata().getAnnotations().remove("styx-workflow-instance");

    podList.setItems(Arrays.asList(createdPod1, createdPod2));
    when(k8sClient.pods().list()).thenReturn(podList);
    when(k8sClient.pods().withName(RUN_SPEC.executionId())).thenReturn(namedPod1);
    when(k8sClient.pods().withName(RUN_SPEC_2.executionId())).thenReturn(namedPod2);

    kdr.tryPollPods();

    verifyPodNeverDeleted(namedPod1);
    verifyPodNeverDeleted(namedPod2);
  }

  @Test
  public void shouldNotDeleteWantedStyxPods() {
    final Pod createdPod1 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE, RUN_SPEC, SECRET_SPEC);
    final Pod createdPod2 = KubernetesDockerRunner.createPod(WORKFLOW_INSTANCE_2, RUN_SPEC, SECRET_SPEC);

    createdPod1.setStatus(podStatus1);
    createdPod2.setStatus(podStatus2);
    when(podStatus1.getPhase()).thenReturn("Pending");
    when(podStatus2.getPhase()).thenReturn("Pending");
    podList.setItems(Arrays.asList(createdPod1, createdPod2));
    when(k8sClient.pods().list()).thenReturn(podList);
    when(k8sClient.pods().withName(RUN_SPEC.executionId())).thenReturn(namedPod1);
    when(k8sClient.pods().withName(RUN_SPEC_2.executionId())).thenReturn(namedPod2);
    when(namedPod1.get()).thenReturn(createdPod1);
    when(namedPod1.get()).thenReturn(createdPod2);

    setupActiveInstances(RunState.State.RUNNING, RUN_SPEC.executionId(), RUN_SPEC_2.executionId());

    kdr.tryPollPods();

    verify(k8sClient.pods(), never()).delete(any(Pod.class));
    verify(k8sClient.pods(), never()).delete(any(Pod[].class));
    verify(k8sClient.pods(), never()).delete(anyListOf(Pod.class));
    verify(k8sClient.pods(), never()).delete();
  }

  private void setupActiveInstances(RunState.State state, String podName1, String podName2) {
    StateData stateData = StateData.newBuilder().executionId(podName1).build();
    StateData stateData2 = StateData.newBuilder().executionId(podName2).build();
    Map<WorkflowInstance, RunState> map = new HashMap<>();
    RunState runState = RunState.create(WORKFLOW_INSTANCE, state, stateData);
    RunState runState2 = RunState.create(WORKFLOW_INSTANCE_2, state, stateData2);
    map.put(WORKFLOW_INSTANCE, runState);
    map.put(WORKFLOW_INSTANCE_2, runState2);
    when(stateManager.getActiveState(WORKFLOW_INSTANCE)).thenReturn(Optional.of(runState));
    when(stateManager.getActiveState(WORKFLOW_INSTANCE_2)).thenReturn(Optional.of(runState2));
    when(stateManager.getActiveStates()).thenReturn(map);
  }

  private void verifyPodNeverDeleted(PodResource<Pod, DoneablePod> pod) {
    verify(k8sClient.pods(), never()).delete(any(Pod.class));
    verify(k8sClient.pods(), never()).delete(any(Pod[].class));
    verify(k8sClient.pods(), never()).delete(anyListOf(Pod.class));
    verify(k8sClient.pods(), never()).delete();
    verify(pod, never()).delete();
  }
}
