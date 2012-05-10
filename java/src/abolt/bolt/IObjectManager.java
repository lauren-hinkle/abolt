package abolt.bolt;

import java.util.HashMap;

import abolt.lcmtypes.object_data_t;

public interface IObjectManager {
	HashMap<Integer, BoltObject> getObjects();
	object_data_t[] getObjectData();
	int getSelectedId();
}
