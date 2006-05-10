/*
Copyright (c) 2002,2003, Sosnoski Software Solutions, Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
 * Neither the name of JiBX nor the names of its contributors may be used
   to endorse or promote products derived from this software without specific
   prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package multiple;

import java.util.ArrayList;

/**
 * Route bean with split route information.
 *
 * @author Dennis M. Sosnoski
 * @version 0.8
 */

public class SplitRouteBean
{
	private AirportBean m_from;
	private AirportBean m_to;
	private ArrayList m_flights;
	
	public SplitRouteBean() {
		m_flights = new ArrayList();
	}
	public void setFrom(AirportBean from) {
		m_from = from;
	}
	public AirportBean getFrom() {
		return m_from;
	}
	public void setTo(AirportBean to) {
		m_to = to;
	}
	public AirportBean getTo() {
		return m_to;
	}
	public ArrayList getFlights() {
		return m_flights;
	}
	public void addFlight(SplitFlightBean flight) {
		m_flights.add(flight);
	}
	public boolean equals(Object obj) {
		if (obj instanceof SplitRouteBean) {
			SplitRouteBean compare = (SplitRouteBean)obj;
			return Utils.equalAirports(m_to, compare.m_to) && 
				Utils.equalAirports(m_from, compare.m_from) &&
				Utils.equalLists(m_flights, compare.m_flights);
		} else {
			return false;
		}
	}
}