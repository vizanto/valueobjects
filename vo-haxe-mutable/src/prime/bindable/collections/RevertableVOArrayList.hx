/*
 * Copyright (c) 2010, The PrimeVC Project Contributors
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE PRIMEVC PROJECT CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE PRIMVC PROJECT CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 *
 *
 * Authors:
 *  Danny Wilson	<danny @ prime.vc>
 */
package prime.bindable.collections;
 import prime.bindable.collections.VOArrayList;	//imports VOArrayListUtil
 import prime.signals.Signal1;
 import prime.tools.valueobjects.ObjectChangeSet;
 import prime.tools.valueobjects.ValueObjectBase;
  using prime.utils.BitUtil;
  using prime.bindable.collections.ListChange;
  using prime.utils.FastArray;
  using prime.utils.IfUtil;


private typedef ListFlags = prime.bindable.collections.RevertableArrayList.RevertableArrayListFlags;
private typedef BindFlags = prime.bindable.RevertableBindableFlags;


/**
 * A specialized RevertableArrayList for ValueObjects.
 * 
 * Used to support bubbling of ObjectChangeSets.
 *  
 *  Unfortunately it copies pretty much everything from RevertableArrayList, because the haXe compiler crashes if we extend it instead.
 * 
 * @author Danny Wilson
 * @creation-date Dec 20, 2010
 */
class RevertableVOArrayList<DataType> extends ReadOnlyArrayList<DataType> implements IRevertableList<DataType>
{
	private var changeHandlerFn : ObjectChangeSet -> Void;
	public  var itemChange : Signal1<ObjectChangeSet>;
	
	public function new( wrapAroundList:FastArray<Dynamic> = null )
	{
		super(if (wrapAroundList == null) null else #if x_flash10 flash.Vector.convert #else cast #end(wrapAroundList));
		itemChange = new Signal1();
		flags = ListFlags.REMEMBER_CHANGES;
	}
	
	override public function dispose()
	{
		Assert.isNotNull(itemChange);

		if (changeHandlerFn != null)
			setChangeHandler(null);
		
		itemChange.dispose();
		itemChange = null;
		super.dispose();
	}
	
	
	public function setChangeHandler(changeHandler : ObjectChangeSet -> Void)
	{
		this.changeHandlerFn = changeHandler;
		VOArrayListUtil.setChangeHandler(this, list, changeHandler);
	}
	
	//
	// Revertable stuff
	//
	
	/**
	 * Keeps track of settings.
	 */
	public var flags : Int;
	
	/**
	 * List with all the changes that are made when the list is in editing mode.
	 */
	public var changes (default,null) : FastArray<ListChange<DataType>>;

#if debug
	@:keep public #if !noinline inline #end function readFlags ()
		return BindFlags.readProperties(flags);
#end
	
	
	
	
	
	
	@:keep public #if !noinline inline #end function rememberChanges (enabled:Bool = true)				flags = enabled ? flags.set(ListFlags.REMEMBER_CHANGES) : flags.unset(ListFlags.REMEMBER_CHANGES);
	@:keep public #if !noinline inline #end function dispatchChangesBeforeCommit (enabled:Bool = true)	flags = enabled ? flags.set(BindFlags.DISPATCH_CHANGES_BEFORE_COMMIT) : flags.unset(BindFlags.DISPATCH_CHANGES_BEFORE_COMMIT);
	
	
	//
	// EDITABLE VALUE-OBJECT METHODS
	//
	
	@:keep public #if !noinline inline #end function isEmpty()
	{
		return this.length == 0;
	}
	
	
	@:keep public #if !noinline inline #end function beginEdit ()
	{
		if (flags.hasNone( BindFlags.IN_EDITMODE ))
		{
			flags = flags.set( BindFlags.IN_EDITMODE );
			
			if (flags.has(ListFlags.REMEMBER_CHANGES))
				changes = FastArrayUtil.create();
		}
	}
	
	
	public  function commitEdit ()
	{
		// Check if REMEMBER_CHANGES is not set (value changed) and any dispatch flag is set.
		if (changes != null && flags.has(ListFlags.REMEMBER_CHANGES) && flags.hasNone( BindFlags.DISPATCH_CHANGES_BEFORE_COMMIT ))
			while (changes.length > 0) {
				var listChange = changes.shift();
				Assert.isNotNull( listChange );
				change.send( listChange );
			}
		
		stopEdit();
	}
	
	
	public  function cancelEdit ()
	{
		if (changes != null && flags.hasAll( BindFlags.IN_EDITMODE | ListFlags.REMEMBER_CHANGES))
		{
			var f = flags;
			flags = flags.unset( ListFlags.REMEMBER_CHANGES );
			while (changes.length > 0)
				this.undoListChange( changes.pop() );
			
			flags = f;
		}
		
		stopEdit();
	}
	
	
	@:keep private #if !noinline inline #end function stopEdit ()
	{
		if (changes != null) {
			changes.removeAll();
			changes = null;
		}
		flags = flags.unset(BindFlags.IN_EDITMODE);
	}


	@:keep public #if !noinline inline #end function isEditable ()
	{
		return flags.has( BindFlags.IN_EDITMODE );
	}
	
	
	
	//
	// IBINDABLE LIST METHODS
	//
	
	
	@:keep private #if !noinline inline #end function addChange (listChange:ListChange<DataType>)
	{
		if (flags.has( ListFlags.REMEMBER_CHANGES ))
			changes.push( listChange );

		if (flags.has( BindFlags.DISPATCH_CHANGES_BEFORE_COMMIT ))
			change.send( listChange );
	}
	
	
	public function removeAll ()
	{
		var f = flags;
		Assert.that( f.has(BindFlags.IN_EDITMODE) );
		
		if (f.hasNone(BindFlags.IN_EDITMODE))
			return;
		
		flags = f.unset( BindFlags.DISPATCH_CHANGES_BEFORE_COMMIT );
		
		while (length > 0)
			remove ( list[ length - 1] );
		
		flags = f;
		if (f.has( BindFlags.DISPATCH_CHANGES_BEFORE_COMMIT ))
			change.send( ListChange.reset );
	}
	
	
	public function add (item:DataType, pos:Int = -1) : DataType
	{
		var f = flags;
#if debug	Assert.that( f.has(BindFlags.IN_EDITMODE), 	this+" doesn't have EDITMODE. "); // Flags: "+BindFlags.readProperties(f) );
			if (f.hasNone(BindFlags.IN_EDITMODE)) 		return item; #end

		pos = list.insertAt(item, pos);
		addChange( ListChange.added( item, pos ) );
		
	//	trace("this.add "+item +"; "+BindFlags.readProperties(flags)+"; "+length);
		if (changeHandlerFn != null)
			cast(item, ValueObjectBase).change.bind(this, changeHandlerFn);
		
		return item;
	}
	
	
	public function remove (item:DataType, curPos:Int = -1) : DataType
	{
		var f = flags;
#if debug	Assert.that( f.has(BindFlags.IN_EDITMODE), 	this+" doesn't have EDITMODE. "); // Flags: "+BindFlags.readProperties(f) );
			if (f.hasNone(BindFlags.IN_EDITMODE)) 		return item; #end
		
		if (curPos == -1)
			curPos = list.indexOf(item);
		
		if (list.removeAt(curPos))
			addChange( ListChange.removed( item, curPos ) );
		
		if (changeHandlerFn != null)
			cast(item, ValueObjectBase).change.unbind(this);
		
		return item;
	}
	
	
	public function move (item:DataType, newPos:Int, curPos:Int = -1) : DataType
	{
		var f = flags;
#if debug	Assert.that( f.has(BindFlags.IN_EDITMODE), 	this+" doesn't have EDITMODE. "); // Flags: "+BindFlags.readProperties(f) );
			if (f.hasNone(BindFlags.IN_EDITMODE)) 		return item; #end
		
		if		(curPos == -1)				curPos = list.indexOf(item);
		if		(newPos > (length - 1))		newPos = length - 1;
		else if (newPos < 0)				newPos = length - newPos;
		
		if (curPos != newPos && list.move(item, newPos, curPos))
			addChange( ListChange.moved( item, newPos, curPos ) );
		
		return item;
	}
	
	
	//
	// VO Stuff
	//
	
	
	
	
	override public function clone () : prime.bindable.collections.IReadOnlyList<DataType>
	{
		var l = new RevertableVOArrayList<DataType>( list.clone() );
		l.flags = flags;
		return l;
	}
	
	
	override public function duplicate () : prime.bindable.collections.IReadOnlyList<DataType>
	{
		var l = new RevertableVOArrayList<DataType>( list.duplicate() );
		l.flags = flags;
		return l;
	}

	
	override public function inject (otherList:FastArray<DataType>)
	{
		VOArrayListUtil.setChangeHandler(this, list, null);
		super.inject(otherList);
		VOArrayListUtil.setChangeHandler(this, otherList, this.changeHandlerFn);
	}
}
