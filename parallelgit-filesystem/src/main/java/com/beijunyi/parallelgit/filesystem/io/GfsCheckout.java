package com.beijunyi.parallelgit.filesystem.io;

import java.io.IOException;
import java.util.*;
import javax.annotation.Nonnull;

import com.beijunyi.parallelgit.filesystem.GfsStatusProvider;
import com.beijunyi.parallelgit.filesystem.GitFileSystem;
import com.beijunyi.parallelgit.utils.io.GitFileEntry;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.*;

import static com.beijunyi.parallelgit.filesystem.utils.GfsPathUtils.toAbsolutePath;
import static org.eclipse.jgit.lib.ObjectId.zeroId;

public class GfsCheckout {

  private static final int HEAD = 0;
  private static final int TARGET = 1;
  private static final int WORKTREE = 2;

  private final GitFileSystem gfs;
  private final GfsStatusProvider status;
  private final ObjectReader reader;
  private final GfsCheckoutChangesCollector changes;

  private Set<String> ignoredFiles;

  public GfsCheckout(@Nonnull GitFileSystem gfs, boolean failOnConflict) {
    this.gfs = gfs;
    this.status = gfs.getStatusProvider();
    this.reader = gfs.getRepository().newObjectReader();
    changes = new GfsCheckoutChangesCollector(failOnConflict);
  }

  public GfsCheckout(@Nonnull GitFileSystem gfs) {
    this(gfs, true);
  }

  @Nonnull
  public GfsCheckout ignoredFiles(@Nonnull Collection<String> ignoredFiles) {
    this.ignoredFiles = new HashSet<>(ignoredFiles);
    return this;
  }

  public boolean checkout(@Nonnull AbstractTreeIterator iterator) throws IOException {
    TreeWalk tw = prepareTreeWalk(iterator);
    mergeTreeWalk(tw);
    changes.applyTo(gfs);
    return !changes.hasConflicts();
  }

  public boolean checkout(@Nonnull AnyObjectId tree) throws IOException {
    return checkout(new CanonicalTreeParser(null, reader, tree));
  }

  public boolean checkout(@Nonnull DirCache cache) throws IOException {
    return checkout(new DirCacheIterator(cache));
  }

  @Nonnull
  public Map<String, GfsCheckoutConflict> getConflicts() {
    return changes.getConflicts();
  }

  @Nonnull
  private TreeWalk prepareTreeWalk(@Nonnull AbstractTreeIterator iterator) throws IOException {
    TreeWalk ret = new NameConflictTreeWalk(gfs.getRepository());
    ret.addTree(new CanonicalTreeParser(null, reader, status.commit().getTree()));
    ret.addTree(iterator);
    ret.addTree(new GfsTreeIterator(gfs));
    return ret;
  }

  private void mergeTreeWalk(@Nonnull TreeWalk tw) throws IOException {
    while(tw.next()) {
      String path = toAbsolutePath(tw.getPathString());
      if(ignoredFiles != null && ignoredFiles.contains(path))
        continue;
      GitFileEntry head = GitFileEntry.forTreeNode(tw, HEAD);
      GitFileEntry target = GitFileEntry.forTreeNode(tw, TARGET);
      GitFileEntry worktree = GitFileEntry.forTreeNode(tw, WORKTREE);
      if(mergeEntries(path, head, target, worktree))
        tw.enterSubtree();
    }
  }

  private boolean mergeEntries(@Nonnull String path, @Nonnull GitFileEntry head, @Nonnull GitFileEntry target, @Nonnull GitFileEntry worktree) throws IOException {
    if(target.equals(worktree) || target.equals(head))
      return false;
    if(head.equals(worktree)) {
      changes.addChange(path, target);
      return isVirtualDirectory(target);
    }
    if(target.isDirectory() && worktree.isDirectory())
      return true;
    changes.addConflict(new GfsCheckoutConflict(path, head, target, worktree));
    return false;
  }

  public static boolean isVirtualDirectory(@Nonnull GitFileEntry entry) {
    return entry.isDirectory() && entry.getId().equals(zeroId());
  }

}
