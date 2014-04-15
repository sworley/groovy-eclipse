/*******************************************************************************
 * Copyright (c) 2000, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Stephan Herrmann - Contributions for
 *     						bug 236385 - [compiler] Warn for potential programming problem if an object is created but not used
 *     						bug 319201 - [null] no warning when unboxing SingleNameReference causes NPE
 *     						bug 349326 - [1.7] new warning for missing try-with-resources
 * 							bug 186342 - [compiler][null] Using annotations for null checking
 *							bug 358903 - Filter practically unimportant resource leak warnings
 *							bug 368546 - [compiler][resource] Avoid remaining false positives found when compiling the Eclipse SDK
 *							bug 370639 - [compiler][resource] restore the default for resource leak warnings
 *							bug 345305 - [compiler][null] Compiler misidentifies a case of "variable can only be null"
 *							bug 388996 - [compiler][resource] Incorrect 'potential resource leak'
 *							bug 403147 - [compiler][null] FUP of bug 400761: consolidate interaction between unboxing, NPE, and deferred checking
 *							Bug 392238 - [1.8][compiler][null] Detect semantically invalid null type annotations
 *							Bug 417295 - [1.8[[null] Massage type annotated null analysis to gel well with deep encoded type bindings.
 *							Bug 400874 - [1.8][compiler] Inference infrastructure should evolve to meet JLS8 18.x (Part G of JSR335 spec)
 *							Bug 424727 - [compiler][null] NullPointerException in nullAnnotationUnsupportedLocation(ProblemReporter.java:5708)
 *							Bug 424710 - [1.8][compiler] CCE in SingleNameReference.localVariableBinding
 *							Bug 425152 - [1.8] [compiler] Lambda Expression not resolved but flow analyzed leading to NPE.
 *							Bug 424205 - [1.8] Cannot infer type for diamond type with lambda on method invocation
 *							Bug 424415 - [1.8][compiler] Eventual resolution of ReferenceExpression is not seen to be happening.
 *							Bug 426366 - [1.8][compiler] Type inference doesn't handle multiple candidate target types in outer overload context
 *							Bug 426290 - [1.8][compiler] Inference + overloading => wrong method resolution ?
 *							Bug 426764 - [1.8] Presence of conditional expression as method argument confuses compiler
 *							Bug 424930 - [1.8][compiler] Regression: "Cannot infer type arguments" error from compiler.
 *							Bug 427483 - [Java 8] Variables in lambdas sometimes can't be resolved
 *							Bug 427438 - [1.8][compiler] NPE at org.eclipse.jdt.internal.compiler.ast.ConditionalExpression.generateCode(ConditionalExpression.java:280)
 *							Bug 426996 - [1.8][inference] try to avoid method Expression.unresolve()? 
 *							Bug 428352 - [1.8][compiler] Resolution errors don't always surface
 *							Bug 429203 - [1.8][compiler] NPE in AllocationExpression.binding
 *							Bug 429430 - [1.8] Lambdas and method reference infer wrong exception type with generics (RuntimeException instead of IOException)
 *     Jesper S Moller <jesper@selskabet.org> - Contributions for
 *							bug 378674 - "The method can be declared as static" is wrong
 *     Andy Clement (GoPivotal, Inc) aclement@gopivotal.com - Contributions for
 *                          Bug 383624 - [1.8][compiler] Revive code generation support for type annotations (from Olivier's work)
 *                          Bug 409245 - [1.8][compiler] Type annotations dropped when call is routed through a synthetic bridge method
 *     Till Brychcy - Contributions for
 *     						bug 413460 - NonNullByDefault is not inherited to Constructors when accessed via Class File
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import static org.eclipse.jdt.internal.compiler.ast.ExpressionContext.*;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.*;
import org.eclipse.jdt.internal.compiler.flow.*;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;
import org.eclipse.jdt.internal.compiler.util.SimpleLookupTable;

public class AllocationExpression extends Expression implements Invocation {

	public TypeReference type;
	public Expression[] arguments;
	public MethodBinding binding;							// exact binding resulting from lookup
	MethodBinding syntheticAccessor;						// synthetic accessor for inner-emulation
	public TypeReference[] typeArguments;
	public TypeBinding[] genericTypeArguments;
	public FieldDeclaration enumConstant; // for enum constant initializations
	protected TypeBinding typeExpected;	  // for <> inference
	public boolean inferredReturnType;

	public FakedTrackingVariable closeTracker;	// when allocation a Closeable store a pre-liminary tracking variable here
	private ExpressionContext expressionContext = VANILLA_CONTEXT;

	 // hold on to this context from invocation applicability inference until invocation type inference (per method candidate):
	private SimpleLookupTable/*<PMB,IC18>*/ inferenceContexts;
	protected InnerInferenceHelper innerInferenceHelper;

	/** Record to keep state between different parts of resolution. */
	ResolutionState suspendedResolutionState;
	class ResolutionState {
		BlockScope scope;
		boolean isDiamond;
		boolean diamondNeedsDeferring;
		boolean argsContainCast;
		boolean cannotInferDiamond; // request the an error be reported in due time
		TypeBinding[] argumentTypes;
		boolean hasReportedError;

		ResolutionState(BlockScope scope, boolean isDiamond, boolean diamonNeedsDeferring,
				boolean argsContainCast, TypeBinding[] argumentTypes)
		{
			this.scope = scope;
			this.isDiamond = isDiamond;
			this.diamondNeedsDeferring = diamonNeedsDeferring;
			this.argsContainCast = argsContainCast;
			this.argumentTypes = argumentTypes;
		}
	}

public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	// check captured variables are initialized in current context (26134)
	checkCapturedLocalInitializationIfNecessary((ReferenceBinding)this.binding.declaringClass.erasure(), currentScope, flowInfo);

	// process arguments
	if (this.arguments != null) {
		boolean analyseResources = currentScope.compilerOptions().analyseResourceLeaks;
		boolean hasResourceWrapperType = analyseResources 
				&& this.resolvedType instanceof ReferenceBinding 
				&& ((ReferenceBinding)this.resolvedType).hasTypeBit(TypeIds.BitWrapperCloseable);
		for (int i = 0, count = this.arguments.length; i < count; i++) {
			flowInfo =
				this.arguments[i]
					.analyseCode(currentScope, flowContext, flowInfo)
					.unconditionalInits();
			// if argument is an AutoCloseable insert info that it *may* be closed (by the target method, i.e.)
			if (analyseResources && !hasResourceWrapperType) { // allocation of wrapped closeables is analyzed specially
				flowInfo = FakedTrackingVariable.markPassedToOutside(currentScope, this.arguments[i], flowInfo, flowContext, false);
			}
			this.arguments[i].checkNPEbyUnboxing(currentScope, flowContext, flowInfo);
		}
		analyseArguments(currentScope, flowContext, flowInfo, this.binding, this.arguments);
	}

	// record some dependency information for exception types
	ReferenceBinding[] thrownExceptions;
	if (((thrownExceptions = this.binding.thrownExceptions).length) != 0) {
		if ((this.bits & ASTNode.Unchecked) != 0 && this.genericTypeArguments == null) {
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=277643, align with javac on JLS 15.12.2.6
			thrownExceptions = currentScope.environment().convertToRawTypes(this.binding.thrownExceptions, true, true);
		}		
		// check exception handling
		flowContext.checkExceptionHandlers(
			thrownExceptions,
			this,
			flowInfo.unconditionalCopy(),
			currentScope);
	}

	// after having analysed exceptions above start tracking newly allocated resource:
	if (currentScope.compilerOptions().analyseResourceLeaks && FakedTrackingVariable.isAnyCloseable(this.resolvedType))
		FakedTrackingVariable.analyseCloseableAllocation(currentScope, flowInfo, this);

	if (this.binding.declaringClass.isMemberType() && !this.binding.declaringClass.isStatic()) {
		// allocating a non-static member type without an enclosing instance of parent type
		// https://bugs.eclipse.org/bugs/show_bug.cgi?id=335845
		currentScope.tagAsAccessingEnclosingInstanceStateOf(this.binding.declaringClass.enclosingType(), false /* type variable access */);
		// Reviewed for https://bugs.eclipse.org/bugs/show_bug.cgi?id=378674 :
		// The corresponding problem (when called from static) is not produced until during code generation
	}
	manageEnclosingInstanceAccessIfNecessary(currentScope, flowInfo);
	manageSyntheticAccessIfNecessary(currentScope, flowInfo);

	// account for possible exceptions thrown by the constructor
	flowContext.recordAbruptExit(); // TODO whitelist of ctors that cannot throw any exc.??

	return flowInfo;
}

public void checkCapturedLocalInitializationIfNecessary(ReferenceBinding checkedType, BlockScope currentScope, FlowInfo flowInfo) {
	if (((checkedType.tagBits & ( TagBits.AnonymousTypeMask|TagBits.LocalTypeMask)) == TagBits.LocalTypeMask)
			&& !currentScope.isDefinedInType(checkedType)) { // only check external allocations
		NestedTypeBinding nestedType = (NestedTypeBinding) checkedType;
		SyntheticArgumentBinding[] syntheticArguments = nestedType.syntheticOuterLocalVariables();
		if (syntheticArguments != null)
			for (int i = 0, count = syntheticArguments.length; i < count; i++){
				SyntheticArgumentBinding syntheticArgument = syntheticArguments[i];
				LocalVariableBinding targetLocal;
				if ((targetLocal = syntheticArgument.actualOuterLocalVariable) == null) continue;
				if (targetLocal.declaration != null && !flowInfo.isDefinitelyAssigned(targetLocal)){
					currentScope.problemReporter().uninitializedLocalVariable(targetLocal, this);
				}
			}
	}
}

public Expression enclosingInstance() {
	return null;
}

public void generateCode(BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
	if (!valueRequired)
		currentScope.problemReporter().unusedObjectAllocation(this);

	int pc = codeStream.position;
	MethodBinding codegenBinding = this.binding.original();
	ReferenceBinding allocatedType = codegenBinding.declaringClass;

	codeStream.new_(this.type, allocatedType);
	boolean isUnboxing = (this.implicitConversion & TypeIds.UNBOXING) != 0;
	if (valueRequired || isUnboxing) {
		codeStream.dup();
	}
	// better highlight for allocation: display the type individually
	if (this.type != null) { // null for enum constant body
		codeStream.recordPositionsFrom(pc, this.type.sourceStart);
	} else {
		// push enum constant name and ordinal
		codeStream.ldc(String.valueOf(this.enumConstant.name));
		codeStream.generateInlinedValue(this.enumConstant.binding.id);
	}

	// handling innerclass instance allocation - enclosing instance arguments
	if (allocatedType.isNestedType()) {
		codeStream.generateSyntheticEnclosingInstanceValues(
			currentScope,
			allocatedType,
			enclosingInstance(),
			this);
	}
	// generate the arguments for constructor
	generateArguments(this.binding, this.arguments, currentScope, codeStream);
	// handling innerclass instance allocation - outer local arguments
	if (allocatedType.isNestedType()) {
		codeStream.generateSyntheticOuterArgumentValues(
			currentScope,
			allocatedType,
			this);
	}
	// invoke constructor
	if (this.syntheticAccessor == null) {
		codeStream.invoke(Opcodes.OPC_invokespecial, codegenBinding, null /* default declaringClass */, this.typeArguments);
	} else {
		// synthetic accessor got some extra arguments appended to its signature, which need values
		for (int i = 0,
			max = this.syntheticAccessor.parameters.length - codegenBinding.parameters.length;
			i < max;
			i++) {
			codeStream.aconst_null();
		}
		codeStream.invoke(Opcodes.OPC_invokespecial, this.syntheticAccessor, null /* default declaringClass */, this.typeArguments);
	}
	if (valueRequired) {
		codeStream.generateImplicitConversion(this.implicitConversion);
	} else if (isUnboxing) {
		// conversion only generated if unboxing
		codeStream.generateImplicitConversion(this.implicitConversion);
		switch (postConversionType(currentScope).id) {
			case T_long :
			case T_double :
				codeStream.pop2();
				break;
			default :
				codeStream.pop();
		}
	}
	codeStream.recordPositionsFrom(pc, this.sourceStart);
}

/**
 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#genericTypeArguments()
 */
public TypeBinding[] genericTypeArguments() {
	return this.genericTypeArguments;
}

public boolean isSuperAccess() {
	return false;
}

public boolean isTypeAccess() {
	return true;
}

/* Inner emulation consists in either recording a dependency
 * link only, or performing one level of propagation.
 *
 * Dependency mechanism is used whenever dealing with source target
 * types, since by the time we reach them, we might not yet know their
 * exact need.
 */
public void manageEnclosingInstanceAccessIfNecessary(BlockScope currentScope, FlowInfo flowInfo) {
	if ((flowInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) != 0) return;
	ReferenceBinding allocatedTypeErasure = (ReferenceBinding) this.binding.declaringClass.erasure();

	// perform some emulation work in case there is some and we are inside a local type only
	if (allocatedTypeErasure.isNestedType()
		&& currentScope.enclosingSourceType().isLocalType()) {

		if (allocatedTypeErasure.isLocalType()) {
			((LocalTypeBinding) allocatedTypeErasure).addInnerEmulationDependent(currentScope, false);
			// request cascade of accesses
		} else {
			// locally propagate, since we already now the desired shape for sure
			currentScope.propagateInnerEmulation(allocatedTypeErasure, false);
			// request cascade of accesses
		}
	}
}

public void manageSyntheticAccessIfNecessary(BlockScope currentScope, FlowInfo flowInfo) {
	if ((flowInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) != 0) return;
	// if constructor from parameterized type got found, use the original constructor at codegen time
	MethodBinding codegenBinding = this.binding.original();

	ReferenceBinding declaringClass;
	if (codegenBinding.isPrivate() && TypeBinding.notEquals(currentScope.enclosingSourceType(), (declaringClass = codegenBinding.declaringClass))) {

		// from 1.4 on, local type constructor can lose their private flag to ease emulation
		if ((declaringClass.tagBits & TagBits.IsLocalType) != 0 && currentScope.compilerOptions().complianceLevel >= ClassFileConstants.JDK1_4) {
			// constructor will not be dumped as private, no emulation required thus
			codegenBinding.tagBits |= TagBits.ClearPrivateModifier;
		} else {
			this.syntheticAccessor = ((SourceTypeBinding) declaringClass).addSyntheticMethod(codegenBinding, isSuperAccess());
			currentScope.problemReporter().needToEmulateMethodAccess(codegenBinding, this);
		}
	}
}

public StringBuffer printExpression(int indent, StringBuffer output) {
	if (this.type != null) { // type null for enum constant initializations
		output.append("new "); //$NON-NLS-1$
	}
	if (this.typeArguments != null) {
		output.append('<');
		int max = this.typeArguments.length - 1;
		for (int j = 0; j < max; j++) {
			this.typeArguments[j].print(0, output);
			output.append(", ");//$NON-NLS-1$
		}
		this.typeArguments[max].print(0, output);
		output.append('>');
	}
	if (this.type != null) { // type null for enum constant initializations
		this.type.printExpression(0, output);
	}
	output.append('(');
	if (this.arguments != null) {
		for (int i = 0; i < this.arguments.length; i++) {
			if (i > 0) output.append(", "); //$NON-NLS-1$
			this.arguments[i].printExpression(0, output);
		}
	}
	return output.append(')');
}

public TypeBinding resolveType(BlockScope scope) {
	// Propagate the type checking to the arguments, and check if the constructor is defined.
	final boolean isDiamond = this.type != null && (this.type.bits & ASTNode.IsDiamond) != 0;
	final CompilerOptions compilerOptions = scope.compilerOptions();
	boolean diamondNeedsDeferring = false;
	long sourceLevel = compilerOptions.sourceLevel;
	if (this.constant != Constant.NotAConstant) {
		this.constant = Constant.NotAConstant;
		if (this.type == null) {
			// initialization of an enum constant
			this.resolvedType = scope.enclosingReceiverType();
		} else {
			this.resolvedType = this.type.resolveType(scope, true /* check bounds*/);
			if (isDiamond && this.typeExpected == null && this.expressionContext == INVOCATION_CONTEXT && sourceLevel >= ClassFileConstants.JDK1_8) {
				if (this.resolvedType != null && this.resolvedType.isValidBinding())
					diamondNeedsDeferring = true;
			}
		}
	} else {
		this.resolvedType = this.type.resolvedType;
	}

	if (this.type != null) {
		checkIllegalNullAnnotation(scope, this.resolvedType);
		checkParameterizedAllocation: {
			if (this.type instanceof ParameterizedQualifiedTypeReference) { // disallow new X<String>.Y<Integer>()
				ReferenceBinding currentType = (ReferenceBinding)this.resolvedType;
				if (currentType == null) return currentType;
				do {
					// isStatic() is answering true for toplevel types
					if ((currentType.modifiers & ClassFileConstants.AccStatic) != 0) break checkParameterizedAllocation;
					if (currentType.isRawType()) break checkParameterizedAllocation;
				} while ((currentType = currentType.enclosingType())!= null);
				ParameterizedQualifiedTypeReference qRef = (ParameterizedQualifiedTypeReference) this.type;
				for (int i = qRef.typeArguments.length - 2; i >= 0; i--) {
					if (qRef.typeArguments[i] != null) {
						scope.problemReporter().illegalQualifiedParameterizedTypeAllocation(this.type, this.resolvedType);
						break;
					}
				}
			}
		}
	}
	// will check for null after args are resolved

	// resolve type arguments (for generic constructor call)
	if (this.typeArguments != null) {
		int length = this.typeArguments.length;
		boolean argHasError = sourceLevel < ClassFileConstants.JDK1_5;
		this.genericTypeArguments = new TypeBinding[length];
		for (int i = 0; i < length; i++) {
			TypeReference typeReference = this.typeArguments[i];
			if ((this.genericTypeArguments[i] = typeReference.resolveType(scope, true /* check bounds*/)) == null) {
				argHasError = true;
			}
			if (argHasError && typeReference instanceof Wildcard) {
				scope.problemReporter().illegalUsageOfWildcard(typeReference);
			}
		}
		if (isDiamond) {
			scope.problemReporter().diamondNotWithExplicitTypeArguments(this.typeArguments);
			return null;
		}
		if (argHasError) {
			if (this.arguments != null) { // still attempt to resolve arguments
				for (int i = 0, max = this.arguments.length; i < max; i++) {
					this.arguments[i].resolveType(scope);
				}
			}
			return null;
		}
	}

	// buffering the arguments' types
	boolean argsContainCast = false;
	TypeBinding[] argumentTypes = Binding.NO_PARAMETERS;
	if (this.arguments != null) {
		boolean argHasError = false;
		int length = this.arguments.length;
		argumentTypes = new TypeBinding[length];
		for (int i = 0; i < length; i++) {
			Expression argument = this.arguments[i];
			if (argument instanceof CastExpression) {
				argument.bits |= DisableUnnecessaryCastCheck; // will check later on
				argsContainCast = true;
			}
			argument.setExpressionContext(INVOCATION_CONTEXT);
			if (this.arguments[i].resolvedType != null) 
				scope.problemReporter().genericInferenceError("Argument was unexpectedly found resolved", this); //$NON-NLS-1$
			if ((argumentTypes[i] = argument.resolveType(scope)) == null) {
				argHasError = true;
			}
			if (sourceLevel >= ClassFileConstants.JDK1_8 && argument.isPolyExpression()) {
				if (this.innerInferenceHelper == null)
					this.innerInferenceHelper = new InnerInferenceHelper();
			}
		}
		if (argHasError) {
			/* https://bugs.eclipse.org/bugs/show_bug.cgi?id=345359, if arguments have errors, completely bail out in the <> case.
			   No meaningful type resolution is possible since inference of the elided types is fully tied to argument types. Do
			   not return the partially resolved type.
			 */
			if (isDiamond) {
				return null; // not the partially cooked this.resolvedType
			}
			if (this.resolvedType instanceof ReferenceBinding) {
				// record a best guess, for clients who need hint about possible constructor match
				TypeBinding[] pseudoArgs = new TypeBinding[length];
				for (int i = length; --i >= 0;) {
					pseudoArgs[i] = argumentTypes[i] == null ? TypeBinding.NULL : argumentTypes[i]; // replace args with errors with null type
				}
				this.binding = scope.findMethod((ReferenceBinding) this.resolvedType, TypeConstants.INIT, pseudoArgs, this, false);
				if (this.binding != null && !this.binding.isValidBinding()) {
					MethodBinding closestMatch = ((ProblemMethodBinding)this.binding).closestMatch;
					// record the closest match, for clients who may still need hint about possible method match
					if (closestMatch != null) {
						if (closestMatch.original().typeVariables != Binding.NO_TYPE_VARIABLES) { // generic method
							// shouldn't return generic method outside its context, rather convert it to raw method (175409)
							closestMatch = scope.environment().createParameterizedGenericMethod(closestMatch.original(), (RawTypeBinding)null);
						}
						this.binding = closestMatch;
						MethodBinding closestMatchOriginal = closestMatch.original();
						if (closestMatchOriginal.isOrEnclosedByPrivateType() && !scope.isDefinedInMethod(closestMatchOriginal)) {
							// ignore cases where method is used from within inside itself (e.g. direct recursions)
							closestMatchOriginal.modifiers |= ExtraCompilerModifiers.AccLocallyUsed;
						}
					}
				}
			}
			return this.resolvedType;
		}
	}
	if (this.resolvedType == null || !this.resolvedType.isValidBinding()) {
		return null;
	}

	// null type denotes fake allocation for enum constant inits
	if (this.type != null && !this.resolvedType.canBeInstantiated()) {
		scope.problemReporter().cannotInstantiate(this.type, this.resolvedType);
		return this.resolvedType;
	}
	ResolutionState state = new ResolutionState(scope, isDiamond, diamondNeedsDeferring, argsContainCast, argumentTypes);
	if (diamondNeedsDeferring) {
		this.suspendedResolutionState = state; // resolving to be continued later (via binding(TypeBinding targetType)).
		return new PolyTypeBinding(this);
	}

	if (!resolvePart2(state))
		return null;
	return resolvePart3(state);
}

/** Second part of resolving that may happen multiple times during overload resolution. */
boolean resolvePart2(ResolutionState state) {
	// TODO: all information persisted during this method may need to be stored per targetType?
	if (state.isDiamond) {
		ReferenceBinding genericType = ((ParameterizedTypeBinding) this.resolvedType).genericType();
		TypeBinding [] inferredTypes = inferElidedTypes((ParameterizedTypeBinding) this.resolvedType, this.resolvedType.enclosingType(), state.argumentTypes, state.scope);
		if (inferredTypes == null) {
			if (!state.diamondNeedsDeferring) {
				state.scope.problemReporter().cannotInferElidedTypes(this);
				state.hasReportedError = true;
				this.resolvedType = null;
			} else {
				state.cannotInferDiamond = true; // defer reporting
			}
			return false;
		}
		this.resolvedType = this.type.resolvedType = state.scope.environment().createParameterizedType(genericType, inferredTypes, ((ParameterizedTypeBinding) this.resolvedType).enclosingType());
		state.cannotInferDiamond = false;
 	}
	ReferenceBinding receiverType = (ReferenceBinding) this.resolvedType;
	this.binding = findConstructorBinding(state.scope, this, receiverType, state.argumentTypes);
	return true;
}

/** Final part of resolving (once): check and report various error conditions. */
TypeBinding resolvePart3(ResolutionState state) {
	if (this.suspendedResolutionState != null && this.suspendedResolutionState.hasReportedError)
		return this.resolvedType;
	this.suspendedResolutionState = null;
	if (state.cannotInferDiamond) {
		state.scope.problemReporter().cannotInferElidedTypes(this);
		return this.resolvedType = null;
	}
	ReferenceBinding allocationType = (ReferenceBinding) this.resolvedType;
	if (!this.binding.isValidBinding()) {
		if (this.binding.declaringClass == null) {
			this.binding.declaringClass = allocationType;
		}
		if (this.type != null && !this.type.resolvedType.isValidBinding()) {
			return null;
		}
		state.scope.problemReporter().invalidConstructor(this, this.binding);
		return this.resolvedType;
	}
	if ((this.binding.tagBits & TagBits.HasMissingType) != 0) {
		state.scope.problemReporter().missingTypeInConstructor(this, this.binding);
	}
	if (isMethodUseDeprecated(this.binding, state.scope, true)) {
		state.scope.problemReporter().deprecatedMethod(this.binding, this);
	}
	if (checkInvocationArguments(state.scope, null, allocationType, this.binding, this.arguments, state.argumentTypes, state.argsContainCast, this)) {
		this.bits |= ASTNode.Unchecked;
	}
	if (this.typeArguments != null && this.binding.original().typeVariables == Binding.NO_TYPE_VARIABLES) {
		state.scope.problemReporter().unnecessaryTypeArgumentsForMethodInvocation(this.binding, this.genericTypeArguments, this.typeArguments);
	}
	if (!state.isDiamond && this.resolvedType.isParameterizedTypeWithActualArguments()) {
 		checkTypeArgumentRedundancy((ParameterizedTypeBinding) this.resolvedType, this.resolvedType.enclosingType(), state.argumentTypes, state.scope);
 	}
	CompilerOptions compilerOptions = state.scope.compilerOptions();
	if (compilerOptions.isAnnotationBasedNullAnalysisEnabled && (this.binding.tagBits & TagBits.IsNullnessKnown) == 0) {
		new ImplicitNullAnnotationVerifier(state.scope.environment(), compilerOptions.inheritNullAnnotations)
				.checkImplicitNullAnnotations(this.binding, null/*srcMethod*/, false, state.scope);
	}
	recordExceptionsForEnclosingLambda(state.scope, this.binding.thrownExceptions);
	return allocationType;
}

/**
 * Check if 'allocationType' illegally has a top-level null annotation.
 */
void checkIllegalNullAnnotation(BlockScope scope, TypeBinding allocationType) {
	if (allocationType != null) {
		// only check top-level null annotation (annots on details are OK):
		long nullTagBits = allocationType.tagBits & TagBits.AnnotationNullMASK;
		if (nullTagBits != 0) {
			Annotation annotation = this.type.findAnnotation(nullTagBits);
			if (annotation != null)
				scope.problemReporter().nullAnnotationUnsupportedLocation(annotation);
		}
	}
}

public TypeBinding[] inferElidedTypes(ParameterizedTypeBinding allocationType, ReferenceBinding enclosingType, TypeBinding[] argumentTypes, final BlockScope scope) {
	/* Given the allocation type and the arguments to the constructor, see if we can synthesize a generic static factory
	   method that would, given the argument types and the invocation site, manufacture a parameterized object of type allocationType.
	   If we are successful then by design and construction, the parameterization of the return type of the factory method is identical
	   to the types elided in the <>.
	 */   
	MethodBinding factory = scope.getStaticFactory(allocationType, enclosingType, argumentTypes, this);
	if (factory instanceof ParameterizedGenericMethodBinding && factory.isValidBinding()) {
		ParameterizedGenericMethodBinding genericFactory = (ParameterizedGenericMethodBinding) factory;
		this.inferredReturnType = genericFactory.inferredReturnType;
		// this is our last chance to inspect the result of the inference that is connected to the throw-away factory binding
		InferenceContext18 infCtx18 = getInferenceContext(genericFactory);
		if (infCtx18 != null && infCtx18.stepCompleted == InferenceContext18.BINDINGS_UPDATED) {
			// refresh argumentTypes from updated bindings in arguments:
			// (this shouldn't be strictly necessary, as FunctionExpression.isCompatibleWith() should give the same result,
			//  but it's probably be a good idea to avoid the necessity to call isCompatibleWith() in the first place). 
			for (int i = 0; i < argumentTypes.length; i++) {
				if (argumentTypes[i] instanceof PolyTypeBinding)
					argumentTypes[i] = this.arguments[i].resolvedType;
			}
		}
		return ((ParameterizedTypeBinding)factory.returnType).arguments;
	}
	return null;
}

public void checkTypeArgumentRedundancy(ParameterizedTypeBinding allocationType, ReferenceBinding enclosingType, TypeBinding[] argumentTypes, final BlockScope scope) {
	if ((scope.problemReporter().computeSeverity(IProblem.RedundantSpecificationOfTypeArguments) == ProblemSeverities.Ignore) || scope.compilerOptions().sourceLevel < ClassFileConstants.JDK1_7) return;
	if (allocationType.arguments == null) return;  // raw binding
	if (this.genericTypeArguments != null) return; // diamond can't occur with explicit type args for constructor
	if (argumentTypes == Binding.NO_PARAMETERS && this.typeExpected instanceof ParameterizedTypeBinding) {
		ParameterizedTypeBinding expected = (ParameterizedTypeBinding) this.typeExpected;
		if (expected.arguments != null && allocationType.arguments.length == expected.arguments.length) {
			// check the case when no ctor takes no params and inference uses the expected type directly
			// eg. X<String> x = new X<String>()
			int i;
			for (i = 0; i < allocationType.arguments.length; i++) {
				if (TypeBinding.notEquals(allocationType.arguments[i], expected.arguments[i]))
					break;
			}
			if (i == allocationType.arguments.length) {
				scope.problemReporter().redundantSpecificationOfTypeArguments(this.type, allocationType.arguments);
				return;
			}	
		}
	}
	TypeBinding [] inferredTypes;
	int previousBits = this.type.bits;
	try {
		// checking for redundant type parameters must fake a diamond, 
		// so we infer the same results as we would get with a diamond in source code:
		this.type.bits |= IsDiamond;
		inferredTypes = inferElidedTypes(allocationType, enclosingType, argumentTypes, scope);
	} finally {
		// reset effects of inference
		this.type.bits = previousBits;
	}
	if (inferredTypes == null) {
		return;
	}
	for (int i = 0; i < inferredTypes.length; i++) {
		if (TypeBinding.notEquals(inferredTypes[i], allocationType.arguments[i]))
			return;
	}
	scope.problemReporter().redundantSpecificationOfTypeArguments(this.type, allocationType.arguments);
}

public void setActualReceiverType(ReferenceBinding receiverType) {
	// ignored
}

public void setDepth(int i) {
	// ignored
}

public void setFieldIndex(int i) {
	// ignored
}

public void traverse(ASTVisitor visitor, BlockScope scope) {
	if (visitor.visit(this, scope)) {
		if (this.typeArguments != null) {
			for (int i = 0, typeArgumentsLength = this.typeArguments.length; i < typeArgumentsLength; i++) {
				this.typeArguments[i].traverse(visitor, scope);
			}
		}
		if (this.type != null) { // enum constant scenario
			this.type.traverse(visitor, scope);
		}
		if (this.arguments != null) {
			for (int i = 0, argumentsLength = this.arguments.length; i < argumentsLength; i++)
				this.arguments[i].traverse(visitor, scope);
		}
	}
	visitor.endVisit(this, scope);
}
/**
 * @see org.eclipse.jdt.internal.compiler.ast.Expression#setExpectedType(org.eclipse.jdt.internal.compiler.lookup.TypeBinding)
 */
public void setExpectedType(TypeBinding expectedType) {
	this.typeExpected = expectedType;
}

public void setExpressionContext(ExpressionContext context) {
	this.expressionContext = context;
}

public boolean isPolyExpression() {
	return isPolyExpression(this.binding);
}
public boolean isPolyExpression(MethodBinding method) {
	return (this.expressionContext == ASSIGNMENT_CONTEXT || this.expressionContext == INVOCATION_CONTEXT) &&
			this.type != null && (this.type.bits & ASTNode.IsDiamond) != 0;
}

/**
 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#invocationTargetType()
 */
public TypeBinding invocationTargetType() {
	return this.typeExpected;
}

public boolean statementExpression() {
	return true;
}

//-- interface Invocation: --
public MethodBinding binding(TypeBinding targetType, boolean reportErrors, Scope scope) {
	if (this.suspendedResolutionState != null && targetType != null) {
		setExpectedType(targetType);
		if (!resolvePart2(this.suspendedResolutionState)) {
			if (reportErrors && !this.suspendedResolutionState.hasReportedError) {
				if (this.suspendedResolutionState.cannotInferDiamond)
					scope.problemReporter().cannotInferElidedTypes(this);
				else
					scope.problemReporter().genericInferenceError("constructor is unexpectedly unresolved", this); //$NON-NLS-1$
				this.suspendedResolutionState.hasReportedError = true;
			}
			return null;
		}
	}
	if (reportErrors && this.binding != null && !this.binding.isValidBinding()) {
		if (this.binding.declaringClass == null)
			this.binding.declaringClass = (ReferenceBinding) this.resolvedType;
		if (this.suspendedResolutionState != null) {
			scope.problemReporter().invalidConstructor(this, this.binding);
			this.suspendedResolutionState.hasReportedError = true;
		}
	}
	return this.binding;
}
public TypeBinding checkAgainstFinalTargetType(TypeBinding targetType, Scope scope) {
	if (this.suspendedResolutionState != null) {
		return resolvePart3(this.suspendedResolutionState);
		// also: should this trigger any propagation to inners, too?
	}
	return super.checkAgainstFinalTargetType(targetType, scope);
}
public Expression[] arguments() {
	return this.arguments;
}

public boolean updateBindings(MethodBinding updatedBinding, TypeBinding targetType) {
	boolean hasUpdate = this.binding != updatedBinding;
	if (this.inferenceContexts != null && this.binding.original() == updatedBinding.original()) {
		InferenceContext18 ctx = (InferenceContext18)this.inferenceContexts.get(this.binding);
		if (ctx != null && updatedBinding instanceof ParameterizedGenericMethodBinding) {
			this.inferenceContexts.put(updatedBinding, ctx);
			// solution may have come from an outer inference, mark now that this (inner) is done (but not deep inners):
			hasUpdate |= ctx.registerSolution(targetType, updatedBinding);
		}
	}
	this.binding = updatedBinding;
	this.resolvedType = updatedBinding.declaringClass;
	return hasUpdate;
}
public void registerInferenceContext(ParameterizedGenericMethodBinding method, InferenceContext18 infCtx18) {
	if (this.inferenceContexts == null)
		this.inferenceContexts = new SimpleLookupTable();
	this.inferenceContexts.put(method, infCtx18);
	MethodBinding original = method.original();
	if (original instanceof SyntheticFactoryMethodBinding) {
		SyntheticFactoryMethodBinding synthOriginal = (SyntheticFactoryMethodBinding)original;
		ParameterizedMethodBinding parameterizedCtor = synthOriginal.applyTypeArgumentsOnConstructor(method.typeArguments);
		this.inferenceContexts.put(parameterizedCtor, infCtx18);
	}
}
public boolean usesInference() {
	return (this.binding instanceof ParameterizedGenericMethodBinding) 
			&& getInferenceContext((ParameterizedGenericMethodBinding) this.binding) != null;
}
public InferenceContext18 getInferenceContext(ParameterizedMethodBinding method) {
	if (this.inferenceContexts == null)
		return null;
	return (InferenceContext18) this.inferenceContexts.get(method);
}
public boolean innersNeedUpdate() {
	return this.innerInferenceHelper != null;
}
public void innerUpdateDone() {
	this.innerInferenceHelper = null;
}
public InnerInferenceHelper innerInferenceHelper() {
	return this.innerInferenceHelper;
}

//-- interface InvocationSite: --
public ExpressionContext getExpressionContext() {
	return this.expressionContext;
}
public InferenceContext18 freshInferenceContext(Scope scope) {
	return new InferenceContext18(scope, this.arguments, this);
}
}