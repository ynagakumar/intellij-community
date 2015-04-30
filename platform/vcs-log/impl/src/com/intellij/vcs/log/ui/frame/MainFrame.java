package com.intellij.vcs.log.ui.frame;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RefreshAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.TextRevisionNumber;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser;
import com.intellij.openapi.vcs.changes.committed.RepositoryChangesBrowser;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowser;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.table.ComponentsListFocusTraversalPolicy;
import com.intellij.vcs.CommittedChangeListForRevision;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.data.VcsLogDataHolder;
import com.intellij.vcs.log.data.VcsLogUiProperties;
import com.intellij.vcs.log.data.VisiblePack;
import com.intellij.vcs.log.graph.PermanentGraph;
import com.intellij.vcs.log.graph.impl.facade.bek.BekSorter;
import com.intellij.vcs.log.impl.VcsLogUtil;
import com.intellij.vcs.log.ui.VcsLogUiImpl;
import com.intellij.vcs.log.ui.actions.IntelliSortChooserPopupAction;
import com.intellij.vcs.log.ui.filter.VcsLogClassicFilterUi;
import com.intellij.vcs.log.ui.tables.GraphTableModel;
import icons.VcsLogIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.*;
import java.util.List;

import static com.intellij.util.ObjectUtils.assertNotNull;
import static com.intellij.util.containers.ContainerUtil.getFirstItem;

public class MainFrame extends JPanel implements TypeSafeDataProvider {

  @NotNull private final VcsLogDataHolder myLogDataHolder;
  @NotNull private final VcsLogUiImpl myUI;
  @NotNull private final Project myProject;
  @NotNull private final VcsLogUiProperties myUiProperties;
  @NotNull private final VcsLog myLog;
  @NotNull private final VcsLogClassicFilterUi myFilterUi;

  @NotNull private final JBLoadingPanel myChangesLoadingPane;
  @NotNull private final VcsLogGraphTable myGraphTable;
  @NotNull private final BranchesPanel myBranchesPanel;
  @NotNull private final DetailsPanel myDetailsPanel;
  @NotNull private final Splitter myDetailsSplitter;
  @NotNull private final JComponent myToolbar;
  @NotNull private final RepositoryChangesBrowser myChangesBrowser;

  public MainFrame(@NotNull VcsLogDataHolder logDataHolder,
                   @NotNull VcsLogUiImpl vcsLogUI,
                   @NotNull Project project,
                   @NotNull VcsLogSettings settings,
                   @NotNull VcsLogUiProperties uiProperties,
                   @NotNull VcsLog log,
                   @NotNull VisiblePack initialDataPack) {
    // collect info
    myLogDataHolder = logDataHolder;
    myUI = vcsLogUI;
    myProject = project;
    myUiProperties = uiProperties;
    myLog = log;
    myFilterUi = new VcsLogClassicFilterUi(myUI, logDataHolder, uiProperties, initialDataPack);

    // initialize components
    myGraphTable = new VcsLogGraphTable(vcsLogUI, logDataHolder, initialDataPack);
    myBranchesPanel = new BranchesPanel(logDataHolder, vcsLogUI, initialDataPack.getRefsModel());
    myBranchesPanel.setVisible(settings.isShowBranchesPanel());
    myDetailsPanel = new DetailsPanel(logDataHolder, myGraphTable, vcsLogUI.getColorManager(), initialDataPack);

    myChangesBrowser = new RepositoryChangesBrowser(project, null, Collections.<Change>emptyList(), null);
    myChangesBrowser.getDiffAction().registerCustomShortcutSet(myChangesBrowser.getDiffAction().getShortcutSet(), getGraphTable());
    myChangesBrowser.getEditSourceAction().registerCustomShortcutSet(CommonShortcuts.getEditSource(), getGraphTable());
    setDefaultEmptyText(myChangesBrowser);
    myChangesLoadingPane = new JBLoadingPanel(new BorderLayout(), project);
    myChangesLoadingPane.add(myChangesBrowser);

    final CommitSelectionListener selectionChangeListener = new CommitSelectionListener(myChangesBrowser);
    myGraphTable.getSelectionModel().addListSelectionListener(selectionChangeListener);
    myGraphTable.getSelectionModel().addListSelectionListener(myDetailsPanel);
    updateWhenDetailsAreLoaded(selectionChangeListener);

    // layout
    myToolbar = createActionsToolbar();

    myDetailsSplitter = new Splitter(true, 0.7f);
    myDetailsSplitter.setFirstComponent(setupScrolledGraph());
    setupDetailsSplitter(myUiProperties.isShowDetails());

    JComponent toolbars = new JPanel(new BorderLayout());
    toolbars.add(myToolbar, BorderLayout.NORTH);
    toolbars.add(myBranchesPanel.createScrollPane(), BorderLayout.CENTER);
    JComponent toolbarsAndTable = new JPanel(new BorderLayout());
    toolbarsAndTable.add(toolbars, BorderLayout.NORTH);
    toolbarsAndTable.add(myDetailsSplitter, BorderLayout.CENTER);

    final Splitter changesBrowserSplitter = new Splitter(false, 0.7f);
    changesBrowserSplitter.setFirstComponent(toolbarsAndTable);
    changesBrowserSplitter.setSecondComponent(myChangesLoadingPane);

    setLayout(new BorderLayout());
    add(changesBrowserSplitter);

    Disposer.register(logDataHolder, new Disposable() {
      public void dispose() {
        myDetailsSplitter.dispose();
        changesBrowserSplitter.dispose();
      }
    });
    myGraphTable.resetDefaultFocusTraversalKeys();
    setFocusTraversalPolicyProvider(true);
    setFocusTraversalPolicy(new MyFocusPolicy());
  }

  /**
   * Informs components that the actual DataPack has been updated (e.g. due to a log refresh). <br/>
   * Components may want to update their fields and/or rebuild.
   *
   * @param dataPack new data pack.
   */
  public void updateDataPack(@NotNull VisiblePack dataPack) {
    myFilterUi.updateDataPack(dataPack);
    myDetailsPanel.updateDataPack(dataPack);
    myGraphTable.updateDataPack(dataPack);
  }

  private void updateWhenDetailsAreLoaded(final CommitSelectionListener selectionChangeListener) {
    myLogDataHolder.getMiniDetailsGetter().addDetailsLoadedListener(new Runnable() {
      @Override
      public void run() {
        myGraphTable.repaint();
      }
    });
    myLogDataHolder.getCommitDetailsGetter().addDetailsLoadedListener(new Runnable() {
      @Override
      public void run() {
        myDetailsPanel.valueChanged(null);
      }
    });
    myLogDataHolder.getContainingBranchesGetter().setTaskCompletedListener(new Runnable() {
      @Override
      public void run() {
        myDetailsPanel.valueChanged(null);
        myGraphTable.repaint(); // we may need to repaint highlighters
      }
    });
  }

  public void setupDetailsSplitter(boolean state) {
    myDetailsSplitter.setSecondComponent(state ? myDetailsPanel : null);
  }

  private JScrollPane setupScrolledGraph() {
    JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(myGraphTable);
    myGraphTable.viewportSet(scrollPane.getViewport());
    return scrollPane;
  }

  private static void setDefaultEmptyText(ChangesBrowser changesBrowser) {
    changesBrowser.getViewer().setEmptyText("");
  }

  @NotNull
  public VcsLogGraphTable getGraphTable() {
    return myGraphTable;
  }

  @NotNull
  public VcsLogFilterUi getFilterUi() {
    return myFilterUi;
  }

  private JComponent createActionsToolbar() {
    AnAction collapseBranchesAction =
      new GraphAction("Collapse linear branches", "Collapse linear branches", VcsLogIcons.CollapseBranches) {
        @Override
        public void actionPerformed(AnActionEvent e) {
          myUI.collapseAll();
        }

        @Override
        public void update(AnActionEvent e) {
          super.update(e);
          if (!myFilterUi.getFilters().getDetailsFilters().isEmpty()) {
            e.getPresentation().setEnabled(false);
          }
          if (myUI.getBekType() == PermanentGraph.SortType.LinearBek) {
            e.getPresentation().setIcon(VcsLogIcons.CollapseMerges);
            e.getPresentation().setText("Collapse all merges");
            e.getPresentation().setDescription("Collapse all merges");
          }
          else {
            e.getPresentation().setIcon(VcsLogIcons.CollapseBranches);
            e.getPresentation().setText("Collapse all linear branches");
            e.getPresentation().setDescription("Collapse all linear branches");
          }
        }
      };

    AnAction expandBranchesAction = new GraphAction("Expand all branches", "Expand all branches", VcsLogIcons.ExpandBranches) {
      @Override
      public void actionPerformed(AnActionEvent e) {
        myUI.expandAll();
      }

      @Override
      public void update(AnActionEvent e) {
        super.update(e);
        if (!myFilterUi.getFilters().getDetailsFilters().isEmpty()) {
          e.getPresentation().setEnabled(false);
        }
        if (myUI.getBekType() == PermanentGraph.SortType.LinearBek) {
          e.getPresentation().setIcon(VcsLogIcons.ExpandMerges);
          e.getPresentation().setText("Expand all merges");
          e.getPresentation().setDescription("Expand all merges");
        }
        else {
          e.getPresentation().setIcon(VcsLogIcons.ExpandBranches);
          e.getPresentation().setText("Expand all linear branches");
          e.getPresentation().setDescription("Expand all linear branches");
        }
      }
    };

    RefreshAction refreshAction = new RefreshAction("Refresh", "Refresh", AllIcons.Actions.Refresh) {
      @Override
      public void actionPerformed(AnActionEvent e) {
        myLogDataHolder.refreshCompletely();
      }

      @Override
      public void update(AnActionEvent e) {
        e.getPresentation().setEnabled(true);
      }
    };

    AnAction showFullPatchAction = new ShowLongEdgesAction();
    AnAction showDetailsAction = new ShowDetailsAction();

    refreshAction.registerShortcutOn(this);

    DefaultActionGroup toolbarGroup =
      new DefaultActionGroup(collapseBranchesAction, expandBranchesAction, showFullPatchAction, refreshAction, showDetailsAction);
    toolbarGroup.add(ActionManager.getInstance().getAction(VcsLogUiImpl.TOOLBAR_ACTION_GROUP));

    DefaultActionGroup mainGroup = new DefaultActionGroup();
    mainGroup.add(myFilterUi.createActionGroup());
    mainGroup.addSeparator();
    if (BekSorter.isBekEnabled()) {
      if (BekSorter.isLinearBekEnabled()) {
        mainGroup.add(new IntelliSortChooserPopupAction());
        // can not register both of the actions in xml file, choosing to register an action for the "outer world"
        // I can of course if linear bek is enabled replace the action on start but why bother
      }
      else {
        mainGroup.add(ActionManager.getInstance().getAction(VcsLogUiImpl.VCS_LOG_INTELLI_SORT_ACTION));
      }
    }
    mainGroup.add(toolbarGroup);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.CHANGES_VIEW_TOOLBAR, mainGroup, true);
    toolbar.setTargetComponent(this);
    return toolbar.getComponent();
  }

  public JComponent getMainComponent() {
    return this;
  }

  public void setBranchesPanelVisible(boolean visible) {
    myBranchesPanel.setVisible(visible);
  }

  @Override
  public void calcData(DataKey key, DataSink sink) {
    if (VcsLogDataKeys.VCS_LOG == key) {
      sink.put(key, myLog);
    }
    else if (VcsLogDataKeys.VCS_LOG_UI == key) {
      sink.put(key, myUI);
    }
    else if (VcsLogDataKeys.VCS_LOG_DATA_PROVIDER == key) {
      sink.put(key, myLogDataHolder);
    }
    else if (VcsDataKeys.CHANGES == key || VcsDataKeys.SELECTED_CHANGES == key) {
      sink.put(key, ArrayUtil.toObjectArray(myChangesBrowser.getCurrentDisplayedChanges(), Change.class));
    }
    else if (VcsDataKeys.CHANGE_LISTS == key) {
      List<VcsFullCommitDetails> details = myUI.getVcsLog().getSelectedDetails();
      if (details.size() > VcsLogUtil.MAX_SELECTED_COMMITS) return;
      sink.put(key, ContainerUtil
        .map2Array(details, CommittedChangeListForRevision.class, new Function<VcsFullCommitDetails, CommittedChangeListForRevision>() {
          @Override
          public CommittedChangeListForRevision fun(@NotNull VcsFullCommitDetails details) {
            return new CommittedChangeListForRevision(details.getSubject(), details.getFullMessage(), details.getCommitter().getName(),
                                                      new Date(details.getCommitTime()), details.getChanges(),
                                                      convertToRevisionNumber(details.getId()));
          }
        }));
    }
    else if (VcsDataKeys.VCS_REVISION_NUMBERS == key) {
      List<CommitId> hashes = myUI.getVcsLog().getSelectedCommits();
      if (hashes.size() > VcsLogUtil.MAX_SELECTED_COMMITS) return;
      sink.put(key, ArrayUtil.toObjectArray(ContainerUtil.map(hashes, new Function<CommitId, VcsRevisionNumber>() {
        @Override
        public VcsRevisionNumber fun(CommitId commitId) {
          return convertToRevisionNumber(commitId.getHash());
        }
      }), VcsRevisionNumber.class));
    }
    else if (VcsDataKeys.VCS == key) {
      List<CommitId> commits = myUI.getVcsLog().getSelectedCommits();
      Collection<VcsLogProvider> logProviders = myUI.getVcsLog().getLogProviders();
      if (logProviders.size() == 1) {
        if (!commits.isEmpty()) {
          sink.put(key, myLogDataHolder.getLogProvider(assertNotNull(getFirstItem(commits)).getRoot()).getSupportedVcs());
        }
        return;
      }
      if (commits.size() > VcsLogUtil.MAX_SELECTED_COMMITS) return;

      Set<VirtualFile> roots = ContainerUtil.map2Set(commits, new Function<CommitId, VirtualFile>() {
        @Override
        public VirtualFile fun(CommitId commitId) {
          return commitId.getRoot();
        }
      });
      if (roots.size() == 1) {
        sink.put(key, myLogDataHolder.getLogProvider(assertNotNull(getFirstItem(roots))).getSupportedVcs());
      }
    }
  }

  @NotNull
  public JComponent getToolbar() {
    return myToolbar;
  }

  public boolean areGraphActionsEnabled() {
    return myGraphTable.getModel() instanceof GraphTableModel && myGraphTable.getRowCount() > 0;
  }

  public void onFiltersChange(@NotNull VcsLogFilterCollection filters) {
    myBranchesPanel.onFiltersChange(filters);
  }

  @NotNull
  private static TextRevisionNumber convertToRevisionNumber(@NotNull Hash hash) {
    return new TextRevisionNumber(hash.asString(), hash.toShortString());
  }

  private class CommitSelectionListener implements ListSelectionListener {
    private final ChangesBrowser myChangesBrowser;
    private ProgressIndicator myLastRequest;

    public CommitSelectionListener(ChangesBrowser changesBrowser) {
      myChangesBrowser = changesBrowser;
    }

    @Override
    public void valueChanged(@Nullable ListSelectionEvent event) {
      if (event != null && event.getValueIsAdjusting()) return;

      if (myLastRequest != null) myLastRequest.cancel();
      myLastRequest = null;

      int rows = getGraphTable().getSelectedRowCount();
      if (rows < 1) {
        myChangesLoadingPane.stopLoading();
        myChangesBrowser.getViewer().setEmptyText("");
        myChangesBrowser.setChangesToDisplay(Collections.<Change>emptyList());
      }
      else {
        myChangesBrowser.setChangesToDisplay(Collections.<Change>emptyList());
        setDefaultEmptyText(myChangesBrowser);
        myChangesLoadingPane.startLoading();

        final EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        myLastRequest = indicator;
        myLog.requestSelectedDetails(new Consumer<List<VcsFullCommitDetails>>() {
          @Override
          public void consume(List<VcsFullCommitDetails> detailsList) {
            if (myLastRequest == indicator && !(indicator.isCanceled())) {
              myLastRequest = null;
              List<Change> changes = ContainerUtil.newArrayList();
              for (VcsFullCommitDetails details : detailsList) {
                changes.addAll(details.getChanges());
              }
              changes = CommittedChangesTreeBrowser.zipChanges(changes);
              myChangesLoadingPane.stopLoading();
              myChangesBrowser.setChangesToDisplay(changes);
            }
          }
        }, indicator);
      }
    }
  }

  private class ShowDetailsAction extends ToggleAction implements DumbAware {

    public ShowDetailsAction() {
      super("Show Details", "Display details panel", AllIcons.Actions.Preview);
    }

    @Override
    public boolean isSelected(AnActionEvent e) {
      return !myProject.isDisposed() && myUiProperties.isShowDetails();
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      setupDetailsSplitter(state);
      if (!myProject.isDisposed()) {
        myUiProperties.setShowDetails(state);
      }
    }
  }

  private class ShowLongEdgesAction extends ToggleAction implements DumbAware {
    public ShowLongEdgesAction() {
      super("Show long edges", "Show long branch edges even if commits are invisible in the current view.", VcsLogIcons.ShowHideLongEdges);
    }

    @Override
    public boolean isSelected(AnActionEvent e) {
      return !myUI.getDataPack().getVisibleGraph().getActionController().areLongEdgesHidden();
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      myUI.setLongEdgeVisibility(state);
    }

    @Override
    public void update(AnActionEvent e) {
      super.update(e);
      e.getPresentation().setEnabled(areGraphActionsEnabled());
    }
  }

  private abstract class GraphAction extends DumbAwareAction {

    public GraphAction(String text, String description, Icon icon) {
      super(text, description, icon);
    }

    @Override
    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(areGraphActionsEnabled());
    }
  }

  private class MyFocusPolicy extends ComponentsListFocusTraversalPolicy {
    @NotNull
    @Override
    protected List<Component> getOrderedComponents() {
      return Arrays.<Component>asList(myGraphTable, myChangesBrowser.getPreferredFocusedComponent());
    }
  }

}
