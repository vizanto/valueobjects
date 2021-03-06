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
 import prime.signals.Signal1;
 import prime.tools.valueobjects.ObjectChangeSet;
 import prime.tools.valueobjects.ValueObjectBase;
  using prime.utils.FastArray;
  using prime.utils.IfUtil;
  using prime.utils.TypeUtil;
 

/**
 * A specialized ArrayList for ValueObjects.
 * 
 * Used to support bubbling of ObjectChangeSets.
 * 
 * @author Danny Wilson
 * @creation-date Dec 20, 2010
 */
class VOArrayList<DataType : prime.core.traits.IValueObject> extends ArrayList<DataType>
{
	private var changeHandlerFn : ObjectChangeSet -> Void;
	public  var itemChange : Signal1<ObjectChangeSet>;
	

	public function new ( wrapAroundList:FastArray<Dynamic> = null )
	{
		super(if (wrapAroundList == null) null else #if x_flash10 flash.Vector.convert #else cast #end(wrapAroundList));
		itemChange = new Signal1();
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

	/**
	 * Method will dispose the VO-list and all the VO's inside of the list
	 */
	public inline function disposeAll ()
	{
		for (item in list)
			item.dispose();
	}
	
	
	public function setChangeHandler(changeHandler : ObjectChangeSet -> Void)
	{
		this.changeHandlerFn = changeHandler;
		VOArrayListUtil.setChangeHandler(this, list, changeHandler);
	}
	
	
	override public function add (item:DataType, pos:Int = -1) : DataType
	{
		super.add(item, pos);
		if (changeHandlerFn != null)
			item.as(ValueObjectBase).change.bind(this, changeHandlerFn);
		
		return item;
	}
	
	
	override public function remove (item:DataType, curPos:Int = -1) : DataType
	{
		super.remove(item, curPos);
		if (changeHandlerFn != null)
			item.as(ValueObjectBase).change.unbind(this);
		
		return item;
	}
	
	
	override public function clone () : IReadOnlyList<DataType>
	{
		return new VOArrayList<DataType>( list.clone() );
	}
	
	
	override public function duplicate () : IReadOnlyList<DataType>
	{
		return new VOArrayList<DataType>( list.duplicate() );
	}


	override public function inject (otherList:FastArray<DataType>)
	{
		VOArrayListUtil.setChangeHandler(this, list, null);
		super.inject(otherList);
		VOArrayListUtil.setChangeHandler(this, otherList, this.changeHandlerFn);
	}
}


/*
 * @author Danny Wilson
 * @creation-date Dec 20, 2010
 */
class VOArrayListUtil
{
	static public function setChangeHandler<T>(owner:Dynamic, list:FastArray<T>, changeHandler : ObjectChangeSet -> Void)
	{
		if (changeHandler.notNull())
			for (i in 0...list.length) {
				var obj = cast(list[i], ValueObjectBase);
				Assert.isNotNull(obj, "VOArrayList item " + i + "is null!");
				obj.change.bind(owner, changeHandler);
			}
		
		else
			for (i in 0...list.length) {
				var obj = cast(list[i], ValueObjectBase);
				Assert.isNotNull(obj, "VOArrayList item " + i + "is null!");
				if (!obj.isDisposed())
					obj.change.unbind(owner);
			}
	}
}
