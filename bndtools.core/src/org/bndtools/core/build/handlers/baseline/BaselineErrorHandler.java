package org.bndtools.core.build.handlers.baseline;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.bndtools.build.api.AbstractBuildErrorDetailsHandler;
import org.bndtools.build.api.MarkerData;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.ui.IMarkerResolution;
import bndtools.Logger;

import aQute.bnd.build.Project;
import aQute.bnd.differ.Baseline.Info;
import aQute.bnd.properties.IRegion;
import aQute.bnd.properties.LineType;
import aQute.bnd.properties.PropertiesLineReader;
import aQute.bnd.service.diff.Delta;
import aQute.bnd.service.diff.Diff;
import aQute.bnd.service.diff.Tree;
import aQute.bnd.service.diff.Type;
import aQute.lib.io.IO;
import aQute.service.reporter.Report.Location;

public class BaselineErrorHandler extends AbstractBuildErrorDetailsHandler {

    private static final String PACKAGEINFO = "packageinfo";
    private static final String PROP_SUGGESTED_VERSION = "suggestedVersion";

    public List<MarkerData> generateMarkerData(IProject project, Project model, Location location) throws Exception {
        List<MarkerData> result = new LinkedList<MarkerData>();

        Info baselineInfo = (Info) location.details;
        IJavaProject javaProject = JavaCore.create(project);

        result.addAll(generatePackageInfoMarkers(baselineInfo, javaProject, location.message));
        result.addAll(generateStructuralChangeMarkers(baselineInfo, javaProject));

        return result;
    }

    List<MarkerData> generatePackageInfoMarkers(Info baselineInfo, IJavaProject javaProject, String message) throws JavaModelException {
        for (IClasspathEntry entry : javaProject.getRawClasspath()) {
            if (IClasspathEntry.CPE_SOURCE == entry.getEntryKind()) {
                IPath entryPath = entry.getPath();
                IPath pkgPath = entryPath.append(baselineInfo.packageName.replace('.', '/'));

                // TODO: handle package-info.java

                IPath pkgInfoPath = pkgPath.append(PACKAGEINFO);
                IFile pkgInfoFile = javaProject.getProject().getWorkspace().getRoot().getFile(pkgInfoPath);
                if (pkgInfoFile != null && pkgInfoFile.exists()) {
                    Map<String,Object> attribs = new HashMap<String,Object>();
                    attribs.put(IMarker.MESSAGE, message.trim());
                    attribs.put(PROP_SUGGESTED_VERSION, baselineInfo.suggestedVersion.toString());

                    LineLocation lineLoc = findVersionLocation(pkgInfoFile.getLocation().toFile());
                    if (lineLoc != null) {
                        attribs.put(IMarker.LINE_NUMBER, lineLoc.lineNum);
                        attribs.put(IMarker.CHAR_START, lineLoc.start);
                        attribs.put(IMarker.CHAR_END, lineLoc.end);
                    }

                    return Collections.singletonList(new MarkerData(pkgInfoFile, attribs, true));
                }
            }
        }

        return Collections.emptyList();
    }

    List<MarkerData> generateStructuralChangeMarkers(Info baselineInfo, IJavaProject javaProject) throws JavaModelException {
        List<MarkerData> markers = new LinkedList<MarkerData>();

        Delta packageDelta = baselineInfo.packageDiff.getDelta();

        // Iterate into the package member diffs
        for (Diff pkgMemberDiff : baselineInfo.packageDiff.getChildren()) {
            // Skip deltas that have lesser significance than the overall package delta
            if (pkgMemberDiff.getDelta().ordinal() < packageDelta.ordinal())
                continue;

            Tree pkgMember = pkgMemberDiff.getNewer();
            if (Type.INTERFACE == pkgMember.getType() || Type.CLASS == pkgMember.getType()) {
                String className = pkgMember.getName();

                // Iterate into the class member diffs
                for (Diff classMemberDiff : pkgMemberDiff.getChildren()) {
                    // Skip deltas that have lesser significance than the overall package delta (again)
                    if (classMemberDiff.getDelta().ordinal() < packageDelta.ordinal())
                        continue;

                    if (Delta.ADDED == classMemberDiff.getDelta()) {
                        Tree classMember = classMemberDiff.getNewer();
                        if (Type.METHOD == classMember.getType())
                            markers.addAll(generateAddedMethodMarker(javaProject, className, classMember.getName(), packageDelta));
                    } else if (Delta.REMOVED == classMemberDiff.getDelta()) {
                        // TODO
                    }
                }
            }
        }

        return markers;
    }

    List<MarkerData> generateAddedMethodMarker(IJavaProject javaProject, String className, final String methodName, final Delta packageDelta) throws JavaModelException {
        final List<MarkerData> markers = new LinkedList<MarkerData>();

        IType type = javaProject.findType(className);
        if (type == null)
            return markers;

        final ICompilationUnit cunit = type.getCompilationUnit();
        if (cunit == null)
            return markers; // not a source type

        ASTParser parser = ASTParser.newParser(AST.JLS4);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(cunit);
        parser.setResolveBindings(true);
        CompilationUnit compilation = (CompilationUnit) parser.createAST(null);
        compilation.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration methodDecl) {
                String signature = buildMethodSignature(methodDecl);
                if (signature.equals(methodName)) {
                    // Create the marker attribs here
                    Map<String,Object> attribs = new HashMap<String,Object>();
                    attribs.put(IMarker.CHAR_START, methodDecl.getStartPosition());
                    attribs.put(IMarker.CHAR_END, methodDecl.getStartPosition() + methodDecl.getLength());

                    String message = String.format("This method was added, which requires a %s change to the package.", packageDelta);
                    attribs.put(IMarker.MESSAGE, message);

                    markers.add(new MarkerData(cunit.getResource(), attribs, false));
                }

                return false;
            }
        });

        return markers;
    }

    private String buildMethodSignature(MethodDeclaration method) {
        StringBuilder builder = new StringBuilder();

        builder.append(method.getName());
        builder.append('(');

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();
        for (Iterator<SingleVariableDeclaration> iter = params.iterator(); iter.hasNext();) {
            String paramType;
            SingleVariableDeclaration param = iter.next();
            ITypeBinding typeBinding = param.getType().resolveBinding();
            if (typeBinding != null)
                paramType = typeBinding.getQualifiedName();
            else
                paramType = param.getName().getIdentifier();

            builder.append(paramType);
            if (iter.hasNext())
                builder.append(",");
        }

        builder.append(')');
        return builder.toString();
    }

    @Override
    public List<IMarkerResolution> getResolutions(IMarker marker) {
        List<IMarkerResolution> result = new LinkedList<IMarkerResolution>();

        final String suggestedVersion = marker.getAttribute(PROP_SUGGESTED_VERSION, null);
        if (suggestedVersion != null) {
            result.add(new IMarkerResolution() {
                public void run(IMarker marker) {
                    final IFile file = (IFile) marker.getResource();
                    final IWorkspace workspace = file.getWorkspace();
                    try {
                        workspace.run(new IWorkspaceRunnable() {
                            public void run(IProgressMonitor monitor) throws CoreException {
                                String input = "version " + suggestedVersion;
                                ByteArrayInputStream stream = new ByteArrayInputStream(input.getBytes());
                                file.setContents(stream, false, true, monitor);
                            }
                        }, null);
                    } catch (CoreException e) {
                        Logger.getLogger().logError("Error applying baseline version quickfix.", e);
                    }
                }

                public String getLabel() {
                    return "Change package version to " + suggestedVersion;
                }
            });

        }

        return result;
    }

    @Override
    public List<ICompletionProposal> getProposals(IMarker marker) {
        List<ICompletionProposal> proposals = new LinkedList<ICompletionProposal>();

        String suggestedVersion = marker.getAttribute(PROP_SUGGESTED_VERSION, null);
        int start = marker.getAttribute(IMarker.CHAR_START, 0);
        int end = marker.getAttribute(IMarker.CHAR_END, 0);
        CompletionProposal proposal = new CompletionProposal("version " + suggestedVersion, start, end - start, end, null, "Change package version to " + suggestedVersion, null, null);
        proposals.add(proposal);

        return proposals;
    }

    private LineLocation findVersionLocation(File file) {
        String content;
        try {
            content = IO.collect(file);
            PropertiesLineReader reader = new PropertiesLineReader(content);

            int lineNum = 1;
            LineType type = reader.next();
            while (type != LineType.eof) {
                if (type == LineType.entry) {
                    String key = reader.key();
                    if ("version".equals(key)) {
                        LineLocation loc = new LineLocation();
                        loc.lineNum = lineNum;
                        IRegion region = reader.region();
                        loc.start = region.getOffset();
                        loc.end = region.getOffset() + region.getLength();
                        return loc;
                    }
                }
                type = reader.next();
                lineNum++;
            }
        } catch (Exception e) {
            // ignore
        }

        return null;
    }
}