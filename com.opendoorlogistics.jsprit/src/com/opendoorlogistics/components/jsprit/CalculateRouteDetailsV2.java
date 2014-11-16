package com.opendoorlogistics.components.jsprit;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import jsprit.core.analysis.SolutionAnalyser;
import jsprit.core.problem.Capacity;
import jsprit.core.problem.job.Delivery;
import jsprit.core.problem.job.Job;
import jsprit.core.problem.job.Pickup;
import jsprit.core.problem.job.Service;
import jsprit.core.problem.job.Shipment;
import jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import jsprit.core.problem.solution.route.VehicleRoute;
import jsprit.core.problem.solution.route.activity.End;
import jsprit.core.problem.solution.route.activity.Start;
import jsprit.core.problem.solution.route.activity.TourActivity;
import jsprit.core.problem.solution.route.activity.TourActivity.JobActivity;
import jsprit.core.problem.vehicle.Vehicle;

import com.opendoorlogistics.api.ODLApi;
import com.opendoorlogistics.api.components.ComponentExecutionApi;
import com.opendoorlogistics.api.geometry.LatLong;
import com.opendoorlogistics.api.tables.ODLDatastore;
import com.opendoorlogistics.api.tables.ODLTable;
import com.opendoorlogistics.api.tables.ODLTableReadOnly;
import com.opendoorlogistics.api.tables.ODLTime;
import com.opendoorlogistics.components.jsprit.BuiltVRP.BuiltStopRec;
import com.opendoorlogistics.components.jsprit.BuiltVRP.TravelCostType;
import com.opendoorlogistics.components.jsprit.solution.RouteDetail;
import com.opendoorlogistics.components.jsprit.solution.SolutionDetail;
import com.opendoorlogistics.components.jsprit.solution.StopDetail;
import com.opendoorlogistics.components.jsprit.tabledefinitions.InputTablesDfn;
import com.opendoorlogistics.components.jsprit.tabledefinitions.StopsTableDefn;
import com.opendoorlogistics.components.jsprit.tabledefinitions.VehiclesTableDfn.CostType;
import com.opendoorlogistics.components.jsprit.tabledefinitions.VehiclesTableDfn.RowVehicleIndex;

public class CalculateRouteDetailsV2 {
	private final ODLApi odlApi;
	private final InputTablesDfn dfn;
	private final VRPConfig config;
	private final ODLTableReadOnly jobsTable;
	private final ODLTableReadOnly vehiclesTable;
	private final ODLTableReadOnly stopOrderTable;
	private final BuiltVRP builtProblem;
	// private final Map<String, Integer> stopIdMap;
	private final Map<String, RouteDetail> vehicleIdToRouteDetails;
	private final VehicleRoutingProblemSolution jspritSol;
	private final SolutionAnalyser jspritSA;
	private final SolutionDetail sd;

	public CalculateRouteDetailsV2(VRPConfig conf, ComponentExecutionApi api, ODLDatastore<? extends ODLTable> ioDb) {
		odlApi = api.getApi();
		dfn = new InputTablesDfn(api.getApi(), conf);
		config = conf;
		jobsTable = ioDb.getTableByImmutableId(dfn.stops.tableId);
		vehiclesTable = ioDb.getTableByImmutableId(dfn.vehicles.tableId);
		stopOrderTable = ioDb.getTableByImmutableId(dfn.stopOrder.tableId);
		sd = new SolutionDetail(config.getNbQuantities());
		
		// build map of route details
		vehicleIdToRouteDetails = buildEmptyRouteDetails();

		// build VRP
		builtProblem = buildVRPProblem(conf, api, dfn, ioDb);

		// get the empty (i.e. without stats) stop objects
		buildEmptyStopDetails();

		// add stops for the depots
		buildDepotStops();

		// find and pickups and delivers on different routes or with only one
		// loaded
		// findUnbalancedPickupDelivers();

		// build jsprit solution using the built problem etc
		Map.Entry<VehicleRoutingProblemSolution, SolutionAnalyser> tmp = buildJspritSolution();
		jspritSol = tmp.getKey();
		jspritSA = tmp.getValue();
		
		fillInStopStats();
		
		fillInRouteStats();
		
		fillInSolutionStats();
	}
	

	private void fillInStopStats() {
		for (final RouteDetail routeDetail : vehicleIdToRouteDetails.values()) {
			VehicleRoute vr = routeDetail.temp.jspritRoute;
			int n = routeDetail.stops.size();
			for (int i = 0; i < n; i++) {
				StopDetail stopDetail = routeDetail.stops.get(i);
				TourActivity ta = stopDetail.temporary.jspritTourActivity;

				if (ta == null) {
					continue;
				}

				// if(stopDetail.type.equals(VRPConstants.DEPOT)){
				//
				// if(i==0){
				// // start depot
				// copyQuantities(jspritSA.getLoadAtBeginning(vr),stopDetail.leaveQuantities);
				// copyQuantities(jspritSA.getCapacityViolationAtBeginning(vr),stopDetail.leaveCapacityViolation);
				// }else{
				// // end depot
				// copyQuantities(jspritSA.getLoadAtEnd(vr),stopDetail.arrivalQuantities);
				// copyQuantities(jspritSA.getCapacityViolationAtEnd(vr),stopDetail.arrivalCapacityViolation);
				// }
				// }
				//
				// else if (stopDetail.temporary.builtStopRec != null) {

				// times
				stopDetail.arrivalTime = ta.getArrTime();
				stopDetail.leaveTime = ta.getEndTime();
				stopDetail.waitingTime = jspritSA.getWaitingTimeAtActivity(ta, vr);
				stopDetail.timeWindowViolation = jspritSA.getTimeWindowViolationAtActivity(ta, vr);

				// quantities
				Capacity before = jspritSA.getLoadJustBeforeActivity(ta, vr);
				copyQuantities(before, stopDetail.arrivalQuantities);
				Capacity violationBefore = Capacity.max(Capacity.Builder.newInstance().build(),
						Capacity.subtract(before, routeDetail.temp.jspritVehicle.getType().getCapacityDimensions()));
				copyQuantities(violationBefore, stopDetail.arrivalCapacityViolation);
				copyQuantities(jspritSA.getLoadRightAfterActivity(ta, vr), stopDetail.leaveQuantities);
				copyQuantities(jspritSA.getCapacityViolationAfterActivity(ta, vr), stopDetail.leaveCapacityViolation);

				// check for quantity violations
				for (long cv : stopDetail.leaveCapacityViolation) {
					if (cv > 0) {
						stopDetail.hasViolation = 1;
					}
				}
				// TO DO ... modify solution analyser to get distance & time
				// from last stop, total travel time at stop etc...

				// travel costs
				stopDetail.totalTravelCost[TravelCostType.DISTANCE_KM.ordinal()] = jspritSA.getDistanceAtActivity(ta, vr);
				stopDetail.totalTravelCost[TravelCostType.COST.ordinal()] = jspritSA.getVariableTransportCostsAtActivity(ta, vr);

				// check for violations
				if (jspritSA.hasBackhaulConstraintViolationAtActivity(ta, vr) || jspritSA.hasShipmentConstraintViolationAtActivity(ta, vr)
						|| jspritSA.hasSkillConstraintViolationAtActivity(ta, vr) || stopDetail.timeWindowViolation > 0) {
					stopDetail.hasViolation = 1;
				}
				// }
			}
		}
	}
	
	private void fillInRouteStats() {
		for (final RouteDetail rd : vehicleIdToRouteDetails.values()) {
			VehicleRoute vr = rd.temp.jspritRoute;
			
			rd.travelCosts[TravelCostType.DISTANCE_KM.ordinal()] = jspritSA.getDistance(vr);
			rd.travelCosts[TravelCostType.TIME.ordinal()] = jspritSA.getTransportTime(vr);
			rd.travelCosts[TravelCostType.COST.ordinal()] = jspritSA.getVariableTransportCosts(vr);
			rd.waitingTime = jspritSA.getWaitingTime(vr);
			copyQuantities(jspritSA.getCapacityViolation(vr), rd.capacityViolation);
			copyQuantities(jspritSA.getLoadDelivered(vr), rd.deliveredQuantities);
			copyQuantities(jspritSA.getLoadPickedUp(vr), rd.pickedUpQuantities);
			copyQuantities(jspritSA.getLoadAtBeginning(vr), rd.startQuantities);
			
			rd.pickupsCount = jspritSA.getNumberOfPickups(vr);
			rd.deliveriesCount = jspritSA.getNumberOfDeliveries(vr);
			
			for(StopDetail sd : rd.stops){
				if(sd.hasViolation==1){
					rd.hasViolation=1;
				}
			}
			
			if(jspritSA.hasBackhaulConstraintViolation(vr) || jspritSA.hasShipmentConstraintViolation(vr)||jspritSA.hasSkillConstraintViolation(vr)){
				rd.hasViolation=1;
			}
		}
	}
	
	private void fillInSolutionStats(){
		sd.routesCount = sd.routes.size();
		copyQuantities(jspritSA.getCapacityViolation(), sd.capacityViolation);
		sd.travelCosts[TravelCostType.DISTANCE_KM.ordinal()] =jspritSA.getDistance();
		sd.travelCosts[TravelCostType.TIME.ordinal()] =jspritSA.getTransportTime();
		sd.travelCosts[TravelCostType.COST.ordinal()] =jspritSA.getVariableTransportCosts();
		sd.waitingTime = jspritSA.getWaitingTime();
		copyQuantities(jspritSA.getLoadDelivered(), sd.deliveredQuantities);
		copyQuantities(jspritSA.getLoadPickedUp(), sd.pickedUpQuantities);
		sd.pickupsCount = jspritSA.getNumberOfPickups();
		sd.deliveriesCount = jspritSA.getNumberOfDeliveries();
		
		for(RouteDetail rd:sd.routes){
			if(rd.hasViolation==1){
				sd.hasViolation=1;
			}
		}
		
		if(jspritSA.hasBackhaulConstraintViolation() || jspritSA.hasShipmentConstraintViolation()||jspritSA.hasSkillConstraintViolation()){
			sd.hasViolation=1;
		}		
	}

	private static void copyQuantities(Capacity from, long[] to) {
		for (int i = 0; i < Math.min(from.getNuOfDimensions(), to.length); i++) {
			to[i] = from.get(i);
		}
	}

	private void buildDepotStops() {
		int nq = config.getNbQuantities();
		for (final RouteDetail route : vehicleIdToRouteDetails.values()) {
			int vRow = route.temp.rvi.row;

			// add start depot
			LatLong[] ends = dfn.vehicles.getStartAndEnd(vehiclesTable, vRow);
			StopDetail startDepot = new StopDetail(nq);
			startDepot.stopLatLong = ends[0];
			startDepot.type = VRPConstants.DEPOT;
			startDepot.stopId = VRPConstants.VEHICLE_START_ID + "_" + dfn.vehicles.getBaseId(vehiclesTable, vRow);
			startDepot.stopName = startDepot.stopId;
			startDepot.stopNumber = 0;
			startDepot.startTimeWindow = route.startTimeWindow;
			startDepot.endTimeWindow = route.endTimeWindow;
			startDepot.vehicleId = route.vehicleId;
			startDepot.vehicleName = route.vehicleName;
			startDepot.temporary.rowVehicleIndex = route.temp.rvi;
			route.stops.add(0, startDepot);

			// add end depot
			if (ends[1] != null) {
				StopDetail endDepot = new StopDetail(nq);
				endDepot.stopLatLong = ends[1];
				endDepot.type = VRPConstants.DEPOT;
				endDepot.stopId = VRPConstants.VEHICLE_END_ID + "_" + dfn.vehicles.getBaseId(vehiclesTable, vRow);
				endDepot.stopName = endDepot.stopId;
				endDepot.stopNumber = route.stops.size();
				endDepot.startTimeWindow = route.startTimeWindow;
				endDepot.endTimeWindow = route.endTimeWindow;
				endDepot.vehicleId = route.vehicleId;
				endDepot.vehicleName = route.vehicleName;
				endDepot.temporary.rowVehicleIndex = route.temp.rvi;
				route.stops.add(endDepot);
			}
		}
	}

	private Map.Entry<VehicleRoutingProblemSolution, SolutionAnalyser> buildJspritSolution() {

		// build the vehicle routes
		List<VehicleRoute> vehicleRoutes = new ArrayList<VehicleRoute>();
		for (final RouteDetail routeDetail : vehicleIdToRouteDetails.values()) {

			VehicleRoute.Builder vehicleRouteBuilder = VehicleRoute.Builder.newInstance(routeDetail.temp.jspritVehicle);
			vehicleRouteBuilder.setJobActivityFactory(builtProblem.getJspritProblem().getJobActivityFactory());

			Map<String, List<StopDetail>> stopDetailsByJobId = odlApi.stringConventions().createStandardisedMap();

			// Go through all the stops on a route and add these to the vehicle
			// route builder as service/job.
			for (StopDetail stopDetail : routeDetail.stops) {

				// // don't add an unbalanced pickup / delivery to the jsprit
				// // solution
				// if (stopDetail.temporary.isUnbalancedPickupDelivery) {
				// continue;
				// }

				// check we have a stop record (could be a depot stop otherwise)
				BuiltStopRec stopRec = builtProblem.getBuiltStop(stopDetail.stopId);
				if (stopRec == null) {
					continue;
				}

				// add the stop to the jsprit vehicle route builder
				Job job = stopRec.getJSpritJob();
				switch (stopRec.getType()) {
				case LINKED_DELIVERY:
					vehicleRouteBuilder.addDelivery((Shipment) job);
					break;

				case LINKED_PICKUP:
					vehicleRouteBuilder.addPickup((Shipment) job);
					break;

				case UNLINKED_PICKUP:
					vehicleRouteBuilder.addPickup((Pickup) job);
					break;

				case UNLINKED_DELIVERY:
					vehicleRouteBuilder.addDelivery((Delivery) job);
					break;

				case NORMAL_STOP:
					vehicleRouteBuilder.addService((Service) job);
					break;
				}

				// also save the stop to our 'by job id' structure
				String jobId = stopRec.getJSpritJob().getId();
				List<StopDetail> sameId = stopDetailsByJobId.get(jobId);
				if (sameId == null) {
					sameId = new ArrayList<StopDetail>();
					stopDetailsByJobId.put(jobId, sameId);
				}
				sameId.add(stopDetail);
			}

			routeDetail.temp.jspritRoute = vehicleRouteBuilder.build();
			vehicleRoutes.add(routeDetail.temp.jspritRoute);

			// Save the tour activity objects to the stop details
			int n = routeDetail.temp.jspritRoute.getActivities().size();
			for (int i = 0; i < n; i++) {
				TourActivity activity = routeDetail.temp.jspritRoute.getActivities().get(i);
				if (JobActivity.class.isInstance(activity)) {
					JobActivity ja = (JobActivity) activity;
					List<StopDetail> stops = stopDetailsByJobId.get(ja.getJob().getId());
					if (stops != null) {
						for (StopDetail sd : stops) {
							if (sd.temporary.builtStopRec == builtProblem.getBuiltStop(ja)) {
								sd.temporary.jspritTourActivity = ja;
								break;
							}
						}
					}
				} else if (Start.class.isInstance(activity) && i == 0) {
					if (routeDetail.stops.size() > 0) {
						StopDetail sd = routeDetail.stops.get(0);
						if (sd.type.equals(VRPConstants.DEPOT)) {
							sd.temporary.jspritTourActivity = activity;
						}
					}
				} else if (End.class.isInstance(activity) && i == (n - 1)) {
					if (routeDetail.stops.size() > 1) {
						StopDetail sd = routeDetail.stops.get(routeDetail.stops.size() - 1);
						if (sd.type.equals(VRPConstants.DEPOT)) {
							sd.temporary.jspritTourActivity = activity;
						}
					}
				}
			}
		}

		// get all loaded job ids (note an pickup-deliver with only one end
		// loaded is still counted here (and possibly shouldn't be)
		Set<String> loadedJobIds = odlApi.stringConventions().createStandardisedSet();
		for (final RouteDetail routeDetail : vehicleIdToRouteDetails.values()) {
			for (StopDetail stopDetail : routeDetail.stops) {
				if (stopDetail.temporary.builtStopRec != null) {
					loadedJobIds.add(stopDetail.temporary.builtStopRec.getJSpritJob().getId());
				}
			}
		}

		// find out which jobs are unassigned - including any partially assigned
		// pds
		List<Job> unassignedJobs = new ArrayList<>();
		for (Job job : builtProblem.getJspritProblem().getJobs().values()) {
			if (!loadedJobIds.contains(job.getId())) {
				unassignedJobs.add(job);
			}
		}

		// build the jsprit problem
		VehicleRoutingProblemSolution sol = new VehicleRoutingProblemSolution(vehicleRoutes, unassignedJobs, 0);

		// Create a solution analyser from the solution.
		SolutionAnalyser analyser = new SolutionAnalyser(builtProblem.getJspritProblem(), jspritSol, new SolutionAnalyser.DistanceCalculator() {

			@Override
			public double getDistance(String fromLocationId, String toLocationId) {
				return builtProblem.getJspritProblem().getTransportCosts().getTransportCost(fromLocationId, toLocationId, 0.0, null, null);
			}

		});

		return new AbstractMap.SimpleEntry<VehicleRoutingProblemSolution, SolutionAnalyser>(sol,analyser);
	}

	// private void findUnbalancedPickupDelivers() {
	// // build a map of all the loaded pds
	// Map<String, List<StopDetail>> pdsByJobId =
	// odlApi.stringConventions().createStandardisedMap();
	// for (RouteDetail rd : vehicleIdToRouteDetails.values()) {
	// for (StopDetail sd : rd.stops) {
	// BuiltStopRec rec = sd.temporary.builtStopRec;
	// if (rec != null) {
	// if (rec.getType() == StopType.LINKED_DELIVERY || rec.getType() ==
	// StopType.LINKED_PICKUP) {
	// String jobId = rec.getJSpritJob().getId();
	// List<StopDetail> list = pdsByJobId.get(jobId);
	// if (list == null) {
	// list = new ArrayList<StopDetail>();
	// pdsByJobId.put(jobId, list);
	// }
	//
	// if (rec.getType() == StopType.LINKED_PICKUP) {
	// list.add(0, sd);
	// } else {
	// list.add(list.size(), sd);
	// }
	// }
	// }
	// }
	// }
	//
	// // parse the grouped pds and check for invalid
	// for (List<StopDetail> list : pdsByJobId.values()) {
	// boolean invalid = false;
	//
	// // check for only one loaded
	// if (list.size() == 1) {
	// invalid = true;
	// }
	//
	// // check for different vehicle
	// if (!invalid) {
	// if (!odlApi.stringConventions().equalStandardised(list.get(0).vehicleId,
	// list.get(1).vehicleId)) {
	// invalid = true;
	// }
	// }
	//
	// // check for delivery before pickup
	// if (!invalid) {
	// if (list.get(0).temporary.rowNumberInStopOrderTable >=
	// list.get(1).temporary.rowNumberInStopOrderTable) {
	// invalid = true;
	// }
	// }
	//
	// if (invalid) {
	// for (StopDetail detail : list) {
	// detail.temporary.isUnbalancedPickupDelivery = true;
	// detail.hasViolation = 1;
	// }
	// }
	// }
	// }

	private void buildEmptyStopDetails() {
		// parse route order table getting records for each stop in a list for
		// each route
		int n = stopOrderTable.getRowCount();
		for (int stopOrderRow = 0; stopOrderRow < n; stopOrderRow++) {
			StopDetail stopDetail = new StopDetail(config.getNbQuantities());
			stopDetail.temporary.rowNumberInStopOrderTable = stopOrderRow;

			// identify stop from the built problem
			stopDetail.temporary.builtStopRec = builtProblem.getBuiltStop(stopDetail.stopId);
			if (stopDetail.temporary.builtStopRec == null) {
				throw new RuntimeException("Failed to build or could not find stop record for stop id " + stopDetail.stopId + " in stop-order table on row " + (stopOrderRow + 1)
						+ ".");
			}

			// get vehicleid and routedetails record - if its unknown an
			// exception would have been thrown already
			stopDetail.vehicleId = dfn.stopOrder.getVehicleId(stopOrderTable, stopOrderRow);
			RouteDetail routeDetail = vehicleIdToRouteDetails.get(stopDetail.vehicleId);
			stopDetail.temporary.rowVehicleIndex = routeDetail.temp.rvi;
			stopDetail.vehicleName = routeDetail.vehicleName;

			// fill in stop details
			StopsTableDefn stopDfn = dfn.stops;
			stopDetail.jobId = stopDetail.temporary.builtStopRec.getJSpritJob().getId();
			int stopRow = stopDetail.temporary.builtStopRec.getRowNbInStopsTable();
			stopDetail.stopName = (String) jobsTable.getValueAt(stopRow, stopDfn.name);
			stopDetail.stopNumber = routeDetail.stops.size() + 1;
			stopDetail.stopAddress = (String) jobsTable.getValueAt(stopRow, stopDfn.address);
			stopDetail.stopLatLong = stopDfn.latLong.getLatLong(jobsTable, stopRow, false);
			stopDetail.stopDuration = stopDfn.getDuration(jobsTable, stopRow).getTotalMilliseconds();
			stopDetail.type = stopDfn.getStopType(jobsTable, stopRow).getKeyword();
			ODLTime[] tw = stopDfn.getTW(jobsTable, stopRow);
			if (tw != null) {
				stopDetail.startTimeWindow = (double) tw[0].getTotalMilliseconds();
				stopDetail.endTimeWindow = (double) tw[1].getTotalMilliseconds();
			}

			// // get stop quantities
			// for (int i = 0; i < conf.getNbQuantities(); i++) {
			// int value = dfn.stops.getQuantity(jobs, stopRow, i);
			// switch (detail.temporary.stopType) {
			// case UNLINKED_DELIVERY:
			// case NORMAL_STOP:
			// // loaded at the depot
			// currentRoute.startQuantities[i] += value;
			//
			// // unloaded on the route
			// detail.stopQuantities[i] = -value;
			// break;
			//
			// case LINKED_PICKUP:
			// case UNLINKED_PICKUP:
			// // loaded on the route, not at the depot
			// detail.stopQuantities[i] = value;
			// break;
			//
			// case LINKED_DELIVERY:
			// // unloaded on the route
			// detail.stopQuantities[i] = -value;
			// break;
			//
			// }
			// }

			// add route details for this stop to the current route
			routeDetail.stops.add(stopDetail);
		}
		
		// fill in stops count
		for (final RouteDetail routeDetail : vehicleIdToRouteDetails.values()) {
			routeDetail.stopsCount = routeDetail.stops.size();
		}
	}

	private Map<String, RouteDetail> buildEmptyRouteDetails() {
		// Create the object which identifies vehicle ids
		VehicleIds vehicleIds = new VehicleIds(odlApi, config, dfn, vehiclesTable);

		Map<String, RouteDetail> ret = odlApi.stringConventions().createStandardisedMap();
		for (int row = 0; row < stopOrderTable.getRowCount(); row++) {
			
			// check if routedetails object already built
			String vehicleId = dfn.stopOrder.getVehicleId(stopOrderTable, row);
			if (ret.containsKey(vehicleId)) {
				continue;
			}

			// identifyVehicle throws an exception if not identified
			RouteDetail detail = new RouteDetail(config.getNbQuantities());
			detail.temp.rvi = vehicleIds.identifyVehicle(row, vehicleId);
			detail.temp.rvi.id = vehicleId;
			detail.vehicleId = vehicleId;

			// fill in any vehicle details we have initially
			int vehicleTypeRow = detail.temp.rvi.row;
			detail.vehicleName = dfn.vehicles.getName(vehiclesTable, vehicleTypeRow, detail.temp.rvi.vehicleIndex);

			detail.vehicleName = detail.vehicleName;
			for (int q = 0; q < config.getNbQuantities(); q++) {
				detail.capacity[q] = dfn.vehicles.getCapacity(vehiclesTable, vehicleTypeRow, q);
			}

			detail.costPerKm = dfn.vehicles.getCost(vehiclesTable, vehicleTypeRow, CostType.COST_PER_KM);
			detail.costPerHour = dfn.vehicles.getCost(vehiclesTable, vehicleTypeRow, CostType.COST_PER_HOUR);
			
			// save
			sd.routes.add(detail);
			ret.put(vehicleId, detail);

		}
		return ret;
	}

	private BuiltVRP buildVRPProblem(VRPConfig conf, ComponentExecutionApi api, InputTablesDfn dfn, ODLDatastore<? extends ODLTable> ioDb) {

		// Get vehicle ids in the format expected by the VRP builder
		TreeMap<Integer, List<RowVehicleIndex>> vehiclesToBuild = new TreeMap<>();
		for (RouteDetail rd : vehicleIdToRouteDetails.values()) {

			List<RowVehicleIndex> withinType = vehiclesToBuild.get(rd.temp.rvi.row);
			if (withinType == null) {
				withinType = new ArrayList<>();
				vehiclesToBuild.put(rd.temp.rvi.row, withinType);
			}
			withinType.add(rd.temp.rvi);
		}

		// build the VRP to (a) call matrix generation and (b) create the exact
		// needed vehicles
		BuiltVRP built = BuiltVRP.build(ioDb, conf, vehiclesToBuild, api);

		// set the jsprit object onto the vehicle record
		for (Vehicle vehicle : built.getJspritProblem().getVehicles()) {
			vehicleIdToRouteDetails.get(vehicle.getId()).temp.jspritVehicle = vehicle;
		}
		return built;
	}
	
	public SolutionDetail getSolutionDetail(){
		return sd;
	}
}